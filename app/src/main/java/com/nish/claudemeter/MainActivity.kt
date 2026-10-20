package com.nish.claudemeter

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension

object Gecko {
    private var rt: GeckoRuntime? = null

    fun runtime(c: Context): GeckoRuntime {
        rt?.let { return it }
        val r = GeckoRuntime.create(c.applicationContext)
        rt = r
        val ctl = r.webExtensionController
        ctl.ensureBuiltIn("resource://android/assets/ext_counter/", "counter@claudemeter.local")
            .accept({ _ -> null }, { e -> Log.e("Meter", "counter ext failed", e); null })
        ctl.ensureBuiltIn("resource://android/assets/ext_meter/", "meter@claudemeter.local")
            .accept({ ext ->
                ext?.setMessageDelegate(object : WebExtension.MessageDelegate {
                    override fun onMessage(
                        nativeApp: String,
                        message: Any,
                        sender: WebExtension.MessageSender
                    ): GeckoResult<Any>? {
                        try {
                            val m = if (message is JSONObject) message else JSONObject(message.toString())
                            val u = m.optString("usage", "")
                            if (u.isNotEmpty()) {
                                UsageStore.json = u
                                UsageStore.stamp = System.currentTimeMillis()
                            }
                        } catch (e: Exception) {
                            Log.e("Meter", "bad message", e)
                        }
                        return null
                    }
                }, "browser")
                null
            }, { e -> Log.e("Meter", "meter ext failed", e); null })
        return r
    }
}

class MainActivity : Activity() {
    private lateinit var view: GeckoView
    private lateinit var pop: GeckoView
    private lateinit var session: GeckoSession
    private var popup: GeckoSession? = null
    private var canBack = false
    private var filePrompt: GeckoSession.PromptDelegate.FilePrompt? = null
    private var fileResult: GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? = null
    private val ui = Handler(Looper.getMainLooper())

    private val okHosts = listOf(
        "claude.ai", "claude.com", "anthropic.com", "google.com", "gstatic.com",
        "googleusercontent.com", "apple.com", "cloudflare.com", "stripe.com"
    )

    private fun allowed(u: Uri): Boolean {
        val s = u.scheme ?: return true
        if (s != "http" && s != "https") return true
        val h = u.host ?: return false
        return okHosts.any { h == it || h.endsWith(".$it") }
    }

    private fun closePopup() {
        val p = popup ?: return
        popup = null
        pop.visibility = View.GONE
        pop.releaseSession()
        p.close()
    }

    private fun guard(): GeckoSession.NavigationDelegate = object : GeckoSession.NavigationDelegate {
        override fun onLoadRequest(
            s: GeckoSession,
            r: GeckoSession.NavigationDelegate.LoadRequest
        ): GeckoResult<AllowOrDeny>? {
            val u = Uri.parse(r.uri)
            if (allowed(u)) return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (e: Exception) {}
            return GeckoResult.fromValue(AllowOrDeny.DENY)
        }

        override fun onCanGoBack(s: GeckoSession, b: Boolean) {
            if (s === session) canBack = b
        }

        override fun onNewSession(s: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            closePopup()
            val ns = GeckoSession()
            ns.navigationDelegate = guard()
            ns.contentDelegate = object : GeckoSession.ContentDelegate {
                override fun onCloseRequest(session: GeckoSession) {
                    ui.post { closePopup() }
                }
            }
            popup = ns
            ui.post {
                ns.open(Gecko.runtime(this@MainActivity))
                pop.setSession(ns)
                pop.visibility = View.VISIBLE
            }
            return GeckoResult.fromValue(ns)
        }
    }

    private val prompts = object : GeckoSession.PromptDelegate {
        override fun onFilePrompt(
            s: GeckoSession,
            p: GeckoSession.PromptDelegate.FilePrompt
        ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
            filePrompt = p
            val r = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
            fileResult = r
            val i = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "*/*"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
            try { startActivityForResult(i, 9) } catch (e: Exception) { r.complete(p.dismiss()) }
            return r
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val rt = Gecko.runtime(this)
        val root = FrameLayout(this)
        view = GeckoView(this)
        pop = GeckoView(this)
        pop.visibility = View.GONE
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.addView(pop, FrameLayout.LayoutParams(-1, -1))
        val d = resources.displayMetrics.density
        val btn = TextView(this).apply {
            text = "📊"
            textSize = 16f
            gravity = Gravity.CENTER
            alpha = 0.55f
            setBackgroundColor(0xAA000000.toInt())
            setOnClickListener { startActivity(Intent(this@MainActivity, OverlayActivity::class.java)) }
            setOnLongClickListener { openClip(); true }
        }
        root.addView(
            btn,
            FrameLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt(), Gravity.END or Gravity.CENTER_VERTICAL)
        )
        setContentView(root)
        session = GeckoSession()
        session.navigationDelegate = guard()
        session.promptDelegate = prompts
        session.open(rt)
        view.setSession(session)
        val start = intent?.data
        session.loadUri(if (start != null && allowed(start)) start.toString() else "https://claude.ai")
    }

    private fun openClip() {
        val t = getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        if (t != null && t.startsWith("http") && allowed(Uri.parse(t))) session.loadUri(t)
        else Toast.makeText(this, "Copy a claude.ai link first", Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        i.data?.let { if (allowed(it)) session.loadUri(it.toString()) }
    }

    override fun onActivityResult(req: Int, res: Int, d: Intent?) {
        if (req == 9) {
            val p = filePrompt
            val r = fileResult
            filePrompt = null
            fileResult = null
            if (p == null || r == null) return
            val cd = d?.clipData
            val one = d?.data
            r.complete(
                when {
                    res != RESULT_OK -> p.dismiss()
                    cd != null && cd.itemCount > 1 ->
                        p.confirm(this, Array(cd.itemCount) { cd.getItemAt(it).uri })
                    one != null -> p.confirm(this, one)
                    cd != null && cd.itemCount == 1 -> p.confirm(this, cd.getItemAt(0).uri)
                    else -> p.dismiss()
                }
            )
        } else super.onActivityResult(req, res, d)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (popup != null) closePopup()
        else if (canBack) session.goBack()
        else super.onBackPressed()
    }
}
