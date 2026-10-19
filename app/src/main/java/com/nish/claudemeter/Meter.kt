package com.nish.claudemeter

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.json.JSONTokener
import java.time.OffsetDateTime
import kotlin.math.abs

class OverlayActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            Toast.makeText(this, "Allow overlay, then open Meter Overlay again", Toast.LENGTH_LONG).show()
        } else {
            startForegroundService(Intent(this, MeterService::class.java))
        }
        finish()
    }
}

class MeterService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var bar: TextView? = null
    private var web: WebView? = null
    private val data = LinkedHashMap<String, Pair<Double, Long>>()
    private var note = "loading..."

    private val tick = object : Runnable {
        override fun run() { render(); h.postDelayed(this, 1000) }
    }
    private val loop = object : Runnable {
        override fun run() { poll(); h.postDelayed(this, 60000) }
    }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        fg()
        if (bar != null) { stopSelf(); return START_NOT_STICKY }
        build()
        h.post(tick)
        h.post(loop)
        return START_STICKY
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        try { wm?.removeView(bar) } catch (e: Exception) {}
        web?.destroy()
        super.onDestroy()
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
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val t = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            setPadding(20, 10, 20, 10)
            setBackgroundColor(0xCC000000.toInt())
            text = "Claude Meter..."
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 140 }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f; var moved = false
        t.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = p.x; sy = p.y; tx = e.rawX; ty = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt(); val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) { p.x = sx + dx; p.y = sy + dy; wm?.updateViewLayout(t, p) }
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    if (e.eventTime - e.downTime > 700) stopSelf() else poll()
                }
            }
            true
        }
        wm?.addView(t, p)
        bar = t
        web = WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = Ua.clean(settings.userAgentString)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView, u: String) {
                    if (u.contains("/usage")) v.evaluateJavascript("document.body.innerText") { parse(it) }
                }
            }
        }
    }

    private fun poll() {
        val ck = CookieManager.getInstance().getCookie("https://claude.ai") ?: ""
        val org = ck.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("lastActiveOrg=") }?.substringAfter("=")
        if (org.isNullOrEmpty()) { note = "sign in via Claude Web"; render(); return }
        web?.loadUrl("https://claude.ai/api/organizations/$org/usage")
    }

    private fun parse(raw: String?) {
        try {
            val s = JSONTokener(raw ?: "null").nextValue() as? String ?: throw Exception()
            val o = JSONObject(s)
            data.clear()
            collect("", o)
            note = if (data.isEmpty()) "no usage fields found" else ""
        } catch (e: Exception) {
            note = "can't read usage - open Claude Web once"
        }
        render()
    }

    private fun collect(k: String, o: JSONObject) {
        if (o.has("utilization")) {
            val pct = o.optDouble("utilization", Double.NaN)
            val at = try {
                OffsetDateTime.parse(o.optString("resets_at")).toInstant().toEpochMilli()
            } catch (e: Exception) { 0L }
            if (!pct.isNaN()) data[k] = pct to at
        }
        for (key in o.keys()) {
            val v = o.opt(key)
            if (v is JSONObject) collect(key, v)
        }
    }

    private fun fmt(ms: Long): String {
        if (ms <= 0) return "reset"
        val hrs = ms / 3600000
        val m = (ms / 60000) % 60
        return if (hrs >= 24) "${hrs / 24}d ${hrs % 24}h" else "${hrs}h ${m}m"
    }

    private fun label(k: String) = when (k) {
        "five_hour" -> "5h"
        "seven_day" -> "7d"
        else -> k.replace("_", " ")
    }

    private fun render() {
        val now = System.currentTimeMillis()
        val lines = data.map { (k, v) ->
            val left = if (v.second > 0) " " + fmt(v.second - now) else ""
            label(k) + " " + "%.1f".format(v.first) + "%" + left
        }
        val txt = (lines + (if (note.isNotEmpty()) listOf(note) else emptyList())).joinToString("\n")
        bar?.text = if (txt.isEmpty()) "..." else txt
    }
}
