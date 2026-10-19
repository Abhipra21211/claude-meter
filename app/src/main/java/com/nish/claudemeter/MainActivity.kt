package com.nish.claudemeter

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Message
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

object Ua {
    fun clean(u: String): String =
        u.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
}

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var root: FrameLayout
    private var pop: WebView? = null
    private var chooser: ValueCallback<Array<Uri>>? = null
    private var js = ""
    private var hasDs = false

    private fun closePopup() {
        pop?.let { root.removeView(it); it.destroy() }
        pop = null
    }

    private fun openUrlDialog() {
        val et = EditText(this)
        val cm = getSystemService(ClipboardManager::class.java)
        et.setText(cm.primaryClip?.getItemAt(0)?.text ?: "")
        AlertDialog.Builder(this)
            .setTitle("Open link")
            .setView(et)
            .setPositiveButton("Open") { _, _ ->
                val u = et.text.toString().trim()
                if (u.startsWith("http")) web.loadUrl(u)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        web = WebView(this)
        root = FrameLayout(this)
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        val d = resources.displayMetrics.density
        val btn = TextView(this).apply {
            text = "📊"
            textSize = 16f
            gravity = Gravity.CENTER
            alpha = 0.55f
            setBackgroundColor(0xAA000000.toInt())
            setOnClickListener { startActivity(Intent(this@MainActivity, OverlayActivity::class.java)) }
            setOnLongClickListener { openUrlDialog(); true }
        }
        root.addView(
            btn,
            FrameLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt(), Gravity.END or Gravity.CENTER_VERTICAL)
        )
        setContentView(root)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = Ua.clean(userAgentString)
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        js = assets.open("shim.js").bufferedReader().use { it.readText() } + "\n" +
            assets.open("counter.user.js").bufferedReader().use { it.readText() }
        hasDs = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (hasDs) WebViewCompat.addDocumentStartJavaScript(web, js, setOf("https://claude.ai"))
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme ?: return false
                if (s == "http" || s == "https") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, r.url)) } catch (e: Exception) {}
                return true
            }
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                if (!hasDs && u.startsWith("https://claude.ai")) v.evaluateJavascript(js, null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                w: WebView?, cb: ValueCallback<Array<Uri>>?, p: FileChooserParams?
            ): Boolean {
                if (cb == null || p == null) return false
                chooser?.onReceiveValue(null)
                chooser = cb
                return try { startActivityForResult(p.createIntent(), 7); true }
                catch (e: Exception) { chooser = null; false }
            }

            override fun onCreateWindow(
                v: WebView?, dialog: Boolean, user: Boolean, msg: Message?
            ): Boolean {
                if (msg == null) return false
                closePopup()
                val w = WebView(this@MainActivity)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.javaScriptCanOpenWindowsAutomatically = true
                w.settings.userAgentString = Ua.clean(w.settings.userAgentString)
                CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
                w.webViewClient = WebViewClient()
                w.webChromeClient = object : WebChromeClient() {
                    override fun onCloseWindow(x: WebView?) { closePopup() }
                }
                root.addView(w, FrameLayout.LayoutParams(-1, -1))
                pop = w
                (msg.obj as WebView.WebViewTransport).webView = w
                msg.sendToTarget()
                return true
            }
        }
        if (b == null) web.loadUrl("https://claude.ai")
    }

    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        if (req == 7) {
            chooser?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, d))
            chooser = null
        } else super.onActivityResult(req, res, d)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pop != null) closePopup()
        else if (web.canGoBack()) web.goBack()
        else super.onBackPressed()
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }
}
