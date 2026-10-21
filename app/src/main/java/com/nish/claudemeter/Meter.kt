package com.nish.claudemeter

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.time.OffsetDateTime
import kotlin.math.abs
import kotlin.math.max

object C {
    val BG = 0xF2262624.toInt()
    val EDGE = 0xFF3D3D3A.toInt()
    val TRACK = 0xFF3A3A37.toInt()
    val TEXT = 0xFFFAF9F5.toInt()
    val MUTED = 0xFF9C9A92.toInt()
    val CLAY = 0xFFD97757.toInt()
    val AMBER = 0xFFE3A03C.toInt()
    val RED = 0xFFD0493E.toInt()
}

class BarView(c: Context) : View(c) {
    var pct = 0.0
    var mark = -1.0
    var color = C.CLAY
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        p.color = C.TRACK
        cv.drawRoundRect(0f, 0f, w, h, r, r, p)
        val fw = (w * pct.coerceIn(0.0, 100.0) / 100.0).toFloat()
        if (fw > 0f) {
            p.color = color
            cv.drawRoundRect(0f, 0f, max(fw, h), h, r, r, p)
        }
        if (mark >= 0) {
            val x = (w * mark / 100.0).toFloat()
            p.color = C.TEXT
            cv.drawRect(x - 1.5f, 0f, x + 1.5f, h, p)
        }
    }
}

class Row(
    val box: LinearLayout,
    val label: TextView,
    val value: TextView,
    val bar: BarView,
    val sub: TextView
)

class OverlayActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            Toast.makeText(this, "Allow overlay, then tap the chart button again", Toast.LENGTH_LONG).show()
        } else if (!UsageStore.hasUsageAccess(this)) {
            Toast.makeText(this, "Allow usage access for Claude Meter, then tap the chart button again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } else if (!UsageStore.readerEnabled(this)) {
            Toast.makeText(this, "Turn on Claude Meter reader in Accessibility, then tap the chart button again", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            startForegroundService(Intent(this, MeterService::class.java))
        }
        finish()
    }
}

class MeterService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var panel: LinearLayout? = null
    private var headRow: LinearLayout? = null
    private var title: TextView? = null
    private var age: TextView? = null
    private var body: LinearLayout? = null
    private var noteTv: TextView? = null
    private var bg: GradientDrawable? = null
    private lateinit var sp: SharedPreferences
    private val rows = LinkedHashMap<String, Row>()
    private val data = LinkedHashMap<String, Pair<Double, Long>>()
    private var note = ""
    private var stamp = 0L
    private var seen = 0L
    private var collapsed = false
    private var fgPkg: String? = null
    private var fgFrom = 0L

    private val tick = object : Runnable {
        override fun run() { update(); h.postDelayed(this, 1000) }
    }
    private val loop = object : Runnable {
        override fun run() { poll(); h.postDelayed(this, 5000) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        fg()
        Gecko.runtime(this)
        if (panel != null) { stopSelf(); return START_NOT_STICKY }
        build()
        load()
        sync()
        update()
        h.post(tick)
        h.post(loop)
        return START_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        try { wm?.removeView(panel) } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(sz: Float, col: Int, bold: Boolean): TextView = TextView(this).apply {
        textSize = sz
        setTextColor(col)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun fg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("meter", "Claude Meter", NotificationManager.IMPORTANCE_MIN)
        )
        val n = Notification.Builder(this, "meter")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Claude Meter running")
            .build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    private fun build() {
        sp = getSharedPreferences("meter", MODE_PRIVATE)
        collapsed = sp.getBoolean("collapsed", false)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dot = tv(12f, C.CLAY, true).apply { text = "●  " }
        val t = tv(12f, C.TEXT, true)
        val a = tv(10f, C.MUTED, false)
        val hr = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot)
            addView(t, LinearLayout.LayoutParams(0, -2, 1f))
            addView(a)
        }
        val b = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val n = tv(10f, C.MUTED, false)
        val g = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(C.BG)
            setStroke(dp(1), C.EDGE)
        }
        val pn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = g
            addView(hr)
            addView(b)
            addView(n)
        }
        headRow = hr; title = t; age = a; body = b; noteTv = n; bg = g; panel = pn
        val p = WindowManager.LayoutParams(
            dp(210),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 140 }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        pn.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) { p.x = sx + dx; p.y = sy + dy; wm?.updateViewLayout(pn, p) }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (e.eventTime - e.downTime > 700) stopSelf()
                    else if (e.y < hr.height + dp(10)) toggle()
                    else poll()
                }
            }
            true
        }
        wm?.addView(pn, p)
    }

    private fun toggle() {
        collapsed = !collapsed
        sp.edit().putBoolean("collapsed", collapsed).apply()
        update()
    }

    private fun poll() {
        val s = UsageStore.json
        if (s == null) { note = "open Claude app > Settings > Usage once"; update(); return }
        if (UsageStore.stamp != seen) { seen = UsageStore.stamp; parse(s, seen) } else update()
    }

    private fun parse(s: String, ts: Long) {
        val o = try { JSONObject(s) } catch (e: Exception) {
            note = "not signed in? got: " + s.take(70).replace("\n", " ")
            update(); return
        }
        val fresh = LinkedHashMap<String, Pair<Double, Long>>()
        collect(fresh, "", o)
        if (fresh.isEmpty()) {
            val msg = o.optJSONObject("error")?.optString("message") ?: s.take(90).replace("\n", " ")
            note = "no usage data: $msg"
            update(); return
        }
        data.clear()
        data.putAll(fresh)
        note = ""
        stamp = ts
        save(); alerts(); sync(); update()
    }

    private fun collect(out: LinkedHashMap<String, Pair<Double, Long>>, k: String, o: JSONObject) {
        val known = k == "five_hour" || k == "seven_day"
        if (o.has("utilization") && (known || !o.isNull("utilization"))) {
            val pct = if (o.isNull("utilization")) 0.0 else o.optDouble("utilization", 0.0)
            val at = try {
                OffsetDateTime.parse(o.optString("resets_at")).toInstant().toEpochMilli()
            } catch (e: Exception) { 0L }
            out[k] = pct to at
        }
        for (key in o.keys()) {
            val v = o.opt(key)
            if (v is JSONObject) collect(out, key, v)
            else if (v === JSONObject.NULL && (key == "five_hour" || key == "seven_day")) out[key] = 0.0 to 0L
        }
    }

    private fun alerts() {
        for ((k, v) in data) for (t in intArrayOf(80, 95)) {
            val key = "al_${k}_${v.second}_$t"
            if (v.first >= t && !sp.getBoolean(key, false)) {
                sp.edit().putBoolean(key, true).apply()
                Toast.makeText(this, "Claude ${label(k)} at ${v.first.toInt()}%", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun save() {
        sp.edit()
            .putString("last", data.entries.joinToString(";") { "${it.key}|${it.value.first}|${it.value.second}" })
            .putLong("stamp", stamp)
            .apply()
    }

    private fun load() {
        stamp = sp.getLong("stamp", 0L)
        for (part in (sp.getString("last", "") ?: "").split(";")) {
            val f = part.split("|")
            if (f.size == 3) data[f[0]] = (f[1].toDoubleOrNull() ?: 0.0) to (f[2].toLongOrNull() ?: 0L)
        }
    }

    private fun sync() {
        val b = body ?: return
        for (k in rows.keys.toList()) if (!data.containsKey(k)) {
            b.removeView(rows[k]!!.box)
            rows.remove(k)
        }
        for (k in data.keys) if (!rows.containsKey(k)) {
            val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val l = tv(12f, C.TEXT, true)
            val v = tv(12f, C.TEXT, true)
            top.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
            top.addView(v)
            val bar = BarView(this)
            val s = tv(10f, C.MUTED, false)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, 0)
                addView(top)
                addView(bar, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(4) })
                addView(s, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
            }
            b.addView(box)
            rows[k] = Row(box, l, v, bar, s)
        }
    }

    private fun windowMs(k: String): Long = when {
        k == "five_hour" -> 5L * 3600000
        k.startsWith("seven_day") -> 7L * 24 * 3600000
        else -> 0L
    }

    private fun label(k: String) = when (k) {
        "five_hour" -> "Session (5h)"
        "seven_day" -> "Weekly (7d)"
        "seven_day_sonnet" -> "Sonnet (7d)"
        "seven_day_opus" -> "Opus (7d)"
        "seven_day_omelette" -> "Design (7d)"
        else -> k.replace("_", " ").replaceFirstChar { it.uppercase() }
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "now"
        val hrs = ms / 3600000
        val m = (ms / 60000) % 60
        return if (hrs >= 24) "${hrs / 24}d ${hrs % 24}h" else "${hrs}h ${m}m"
    }

    private fun ago(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) "${s}s ago" else "${s / 60}m ago"
    }

    private fun sum(): String {
        val a = data["five_hour"]
        val w = data["seven_day"]
        return listOfNotNull(
            a?.let { "S ${it.first.toInt()}%" },
            w?.let { "W ${it.first.toInt()}%" }
        ).joinToString(" · ")
    }

    private fun applyVisibility() {
        val pn = panel ?: return
        if (!UsageStore.hasUsageAccess(this)) { pn.visibility = View.VISIBLE; return }
        val now = System.currentTimeMillis()
        if (fgFrom == 0L) fgFrom = now - 2 * 3600000
        try {
            val um = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val ev = um.queryEvents(fgFrom, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) fgPkg = e.packageName
            }
            fgFrom = now
        } catch (ex: Exception) {}
        pn.visibility = if (fgPkg == "com.anthropic.claude") View.VISIBLE else View.GONE
    }

    private fun update() {
        applyVisibility()
        val now = System.currentTimeMillis()
        var worst = 0.0
        for ((k, v) in data) {
            val r = rows[k] ?: continue
            val pct = v.first
            val win = windowMs(k)
            var el = -1.0
            if (v.second > 0 && win > 0)
                el = (100.0 * (1.0 - (v.second - now).toDouble() / win)).coerceIn(0.0, 100.0)
            val col = if (pct >= 85) C.RED else if (pct >= 60) C.AMBER else C.CLAY
            r.label.text = label(k)
            r.value.text = "%.1f%%".format(pct)
            r.value.setTextColor(col)
            r.bar.pct = pct
            r.bar.mark = el
            r.bar.color = col
            r.bar.invalidate()
            val left = if (v.second > 0) "resets in " + fmt(v.second - now) else ""
            val pace = if (el < 0) "" else if (pct > el + 5) "  ·  ▲ fast" else "  ·  ✓ ok"
            r.sub.text = left + pace
            if (pct > worst) worst = pct
        }
        bg?.setStroke(dp(1), if (worst >= 95) C.RED else C.EDGE)
        age?.text = if (stamp > 0) ago(now - stamp) else ""
        title?.text = if (collapsed) "Meter  " + sum() else "Claude Meter  ▾"
        body?.visibility = if (collapsed) View.GONE else View.VISIBLE
        val shown = if (note.isNotEmpty()) note else if (stamp > 0 && now - stamp > 15 * 60000) "stale - open Settings > Usage in Claude" else ""
        noteTv?.text = shown
        noteTv?.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
    }
}
// END
