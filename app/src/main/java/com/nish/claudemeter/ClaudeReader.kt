package com.nish.claudemeter

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

class ClaudeReader : AccessibilityService() {
    private var lastRun = 0L
    private var nodes = 0

    private val gate = Regex("Current session|%\\s*used", RegexOption.IGNORE_CASE)
    private val rx = Regex(
        "(Current session|Weekly limits|All models|Sonnet only|Opus only|Claude Design|Fable)" +
            "|(\\d+(?:\\.\\d+)?)\\s*%\\s*used" +
            "|Resets in\\s+((?:\\d+\\s*(?:days?|d|hours?|hrs?|hr|h|minutes?|mins?|min|m)\\b\\s*)+)" +
            "|Resets\\s+((?:Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*,?\\s+\\d{1,2}(?::\\d{2})?\\s*[AaPp][Mm])",
        RegexOption.IGNORE_CASE
    )
    private val unit = Regex(
        "(\\d+)\\s*(days?|d|hours?|hrs?|hr|h|minutes?|mins?|min|m)\\b",
        RegexOption.IGNORE_CASE
    )
    private val clock = Regex(
        "(Mon|Tue|Wed|Thu|Fri|Sat|Sun)[a-z]*,?\\s+(\\d{1,2})(?::(\\d{2}))?\\s*([AaPp])[Mm]",
        RegexOption.IGNORE_CASE
    )

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val now = System.currentTimeMillis()
        if (now - lastRun < 700) return
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != "com.anthropic.claude") return
        lastRun = now
        nodes = 0
        val sb = StringBuilder()
        walk(root, sb, 0)
        val text = sb.toString()
        if (!gate.containsMatchIn(text)) return
        publish(text, now)
    }

    private fun walk(n: AccessibilityNodeInfo?, sb: StringBuilder, d: Int) {
        if (n == null || d > 40 || nodes > 800) return
        nodes++
        val t = n.text?.toString()
        if (!t.isNullOrBlank()) {
            sb.append(t).append('\n')
        } else {
            val c = n.contentDescription?.toString()
            if (!c.isNullOrBlank()) sb.append(c).append('\n')
        }
        for (i in 0 until n.childCount) walk(n.getChild(i), sb, d + 1)
    }

    private fun keyOf(s: String): String {
        val l = s.lowercase()
        return when {
            l.contains("session") -> "five_hour"
            l.contains("sonnet") -> "seven_day_sonnet"
            l.contains("opus") -> "seven_day_opus"
            l.contains("fable") -> "seven_day_fable"
            l.contains("design") -> "seven_day_omelette"
            else -> "seven_day"
        }
    }

    private fun duration(s: String): Long {
        var ms = 0L
        for (m in unit.findAll(s)) {
            val n = m.groupValues[1].toLong()
            ms += when (m.groupValues[2].lowercase()[0]) {
                'd' -> n * 86400000L
                'h' -> n * 3600000L
                else -> n * 60000L
            }
        }
        return ms
    }

    private fun nextWeekly(s: String, now: Long): Long {
        val m = clock.find(s) ?: return 0L
        val dow = when (m.groupValues[1].lowercase()) {
            "mon" -> DayOfWeek.MONDAY
            "tue" -> DayOfWeek.TUESDAY
            "wed" -> DayOfWeek.WEDNESDAY
            "thu" -> DayOfWeek.THURSDAY
            "fri" -> DayOfWeek.FRIDAY
            "sat" -> DayOfWeek.SATURDAY
            else -> DayOfWeek.SUNDAY
        }
        var h = m.groupValues[2].toInt() % 12
        if (m.groupValues[4].lowercase() == "p") h += 12
        val mi = m.groupValues[3].ifEmpty { "0" }.toInt()
        val nowZ = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
        var t = nowZ.with(TemporalAdjusters.nextOrSame(dow)).with(LocalTime.of(h, mi))
        if (!t.isAfter(nowZ)) t = t.plusWeeks(1)
        return t.toInstant().toEpochMilli()
    }

    private fun publish(text: String, now: Long) {
        val pct = LinkedHashMap<String, Double>()
        val rst = HashMap<String, Long>()
        var label: String? = null
        for (m in rx.findAll(text)) {
            val g = m.groupValues
            val l = label
            when {
                g[1].isNotEmpty() -> label = keyOf(g[1])
                g[2].isNotEmpty() -> if (l != null && !pct.containsKey(l)) pct[l] = g[2].toDouble()
                g[3].isNotEmpty() -> if (l != null && !rst.containsKey(l)) rst[l] = now + duration(g[3])
                g[4].isNotEmpty() -> if (l != null && !rst.containsKey(l)) rst[l] = nextWeekly(g[4], now)
            }
        }
        val o = JSONObject()
        if (pct.isEmpty()) {
            if (!text.contains("Current session", ignoreCase = true)) return
            val i = text.indexOf("used", ignoreCase = true).coerceAtLeast(0)
            val snip = text.substring(maxOf(0, i - 60), minOf(text.length, i + 30)).replace("\n", " | ")
            o.put("error", JSONObject().put("message", snip))
        } else {
            for ((k, v) in pct) {
                val b = JSONObject().put("utilization", v)
                val r = rst[k]
                if (r != null && r > 0) b.put("resets_at", Instant.ofEpochMilli(r).toString())
                o.put(k, b)
            }
        }
        UsageStore.json = o.toString()
        UsageStore.stamp = now
    }
}
// END
