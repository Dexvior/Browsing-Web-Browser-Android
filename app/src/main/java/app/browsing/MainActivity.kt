package app.browsing

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private class Tab(val id: Int, val web: WebView) {
        var title = "New Tab"
        var url = ""
        var progress = 0
        var loading = false
    }

    private lateinit var root: FrameLayout
    private lateinit var holder: FrameLayout
    private lateinit var chrome: WebView

    private val tabs = mutableListOf<Tab>()
    private val pending = mutableListOf<String>()
    private val ui = Handler(Looper.getMainLooper())
    private val pushRun = Runnable { doPush() }

    private var activeId = -1
    private var seq = 0
    private var ready = false
    private var desktop = false
    private var bottomBar = false
    private var full = false
    private var barCss = 58f
    private var searchTpl = "https://duckduckgo.com/?q="
    private var engineName = "DuckDuckGo"
    private var themeName = "dark"
    private var lastBg = "#000000"
    private var lastDark = true

    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) WebView.setWebContentsDebuggingEnabled(true)

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        holder = FrameLayout(this)
        root.addView(holder, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        chrome = WebView(this)
        chrome.setBackgroundColor(Color.TRANSPARENT)
        chrome.isVerticalScrollBarEnabled = false
        chrome.settings.javaScriptEnabled = true
        chrome.settings.domStorageEnabled = true
        chrome.webViewClient = WebViewClient()
        chrome.addJavascriptInterface(Bridge(), "Android")
        root.addView(chrome, FrameLayout.LayoutParams(MATCH_PARENT, (barCss * resources.displayMetrics.density).toInt(), Gravity.TOP))
        setContentView(root)
        relayout()

        urlFrom(intent)?.let { pending.add(it) }
        chrome.loadUrl("file:///android_asset/chrome.html")
    }

    /* ---------- bridge: commands from the interface (chrome.html) ---------- */
    inner class Bridge {
        @JavascriptInterface
        fun cmd(json: String) {
            runOnUiThread { try { handle(JSONObject(json)) } catch (_: Exception) {} }
        }
    }

    private fun handle(o: JSONObject) {
        when (o.getString("c")) {
            "ready" -> onReady()
            "new" -> newTab(o.optString("url", ""), true)
            "go" -> active()?.web?.loadUrl(o.getString("url"))
            "back" -> active()?.web?.let { if (it.canGoBack()) it.goBack() }
            "forward" -> active()?.web?.let { if (it.canGoForward()) it.goForward() }
            "reload" -> active()?.web?.reload()
            "select" -> selectTab(o.getInt("id"))
            "close" -> closeTab(o.getInt("id"))
            "size" -> { barCss = o.getDouble("h").toFloat(); full = o.optBoolean("full"); relayout() }
            "layout" -> { bottomBar = o.getBoolean("bottom"); relayout() }
            "bars" -> setBars(o.getString("bg"), o.getBoolean("dark"))
            "prefs" -> { searchTpl = o.getString("s"); engineName = o.getString("e"); themeName = o.getString("t") }
            "desktop" -> { desktop = o.getBoolean("on"); tabs.forEach { applyUa(it.web) }; active()?.web?.reload() }
            "clear" -> clearData()
            "share" -> share()
        }
    }

    private fun onReady() {
        if (ready) return
        ready = true
        val sp = getSharedPreferences("session", MODE_PRIVATE)
        val arr = try { JSONArray(sp.getString("tabs", "[]")) } catch (_: Exception) { JSONArray() }
        val saved = sp.getInt("active", 0).coerceAtLeast(0)
        for (i in 0 until arr.length()) newTab(arr.optString(i, ""), false)
        if (tabs.isNotEmpty()) selectTab(tabs[minOf(saved, tabs.size - 1)].id)
        pending.forEach { newTab(it, true) }
        pending.clear()
        if (tabs.isEmpty()) newTab("", true)
    }

    /* ---------- layout ---------- */
    private fun relayout() {
        val barPx = (barCss * resources.displayMetrics.density).toInt()
        chrome.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, if (full) MATCH_PARENT else barPx, if (bottomBar) Gravity.BOTTOM else Gravity.TOP)
        val hp = holder.layoutParams as FrameLayout.LayoutParams
        hp.topMargin = if (bottomBar) 0 else barPx
        hp.bottomMargin = if (bottomBar) barPx else 0
        holder.layoutParams = hp
    }

    private fun setBars(bg: String, dark: Boolean) {
        lastBg = bg; lastDark = dark
        val c = try { Color.parseColor(bg) } catch (_: Exception) { Color.BLACK }
        window.statusBarColor = c
        window.navigationBarColor = c
        root.setBackgroundColor(c)
        if (Build.VERSION.SDK_INT >= 30) {
            val m = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else m, m)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }
    }

    /* ---------- tabs ---------- */
    private fun active() = tabs.firstOrNull { it.id == activeId }

    private fun newtabUrl() = "file:///android_asset/newtab.html?type=newtab&s=" + Uri.encode(searchTpl) +
        "&e=" + Uri.encode(engineName) + "&t=" + themeName

    private fun newTab(url: String, activate: Boolean, popup: WebView? = null): Tab {
        val w = popup ?: makeWeb()
        val t = Tab(++seq, w)
        tabs.add(t)
        holder.addView(w)
        attachClients(t)
        if (popup == null) w.loadUrl(if (url.isBlank()) newtabUrl() else url)
        if (activate || tabs.size == 1) selectTab(t.id) else { w.visibility = View.GONE; push() }
        return t
    }

    private fun selectTab(id: Int) {
        activeId = id
        tabs.forEach { it.web.visibility = if (it.id == id) View.VISIBLE else View.GONE }
        push()
        saveSession()
    }

    private fun closeTab(id: Int) {
        val i = tabs.indexOfFirst { it.id == id }
        if (i < 0) return
        val t = tabs.removeAt(i)
        holder.removeView(t.web)
        t.web.destroy()
        if (tabs.isEmpty()) { newTab("", true); return }
        if (id == activeId) selectTab(tabs[minOf(i, tabs.size - 1)].id) else push()
        saveSession()
    }

    private fun saveSession() {
        val a = JSONArray()
        tabs.forEach { a.put(if (it.url.startsWith("file:///android_asset")) "" else it.url) }
        getSharedPreferences("session", MODE_PRIVATE).edit()
            .putString("tabs", a.toString())
            .putInt("active", tabs.indexOfFirst { it.id == activeId })
            .apply()
    }

    /* ---------- web views ---------- */
    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWeb(): WebView {
        val w = WebView(this)
        w.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        val s = w.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        // Do not announce the app package name on every request.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(s, emptySet())
        }
        applyUa(w)
        w.setDownloadListener { url, ua, cd, mime, _ -> download(url, ua, cd, mime) }
        return w
    }

    private fun applyUa(w: WebView) {
        // Normal Chrome-style identity: drop the WebView markers ("; wv" and "Version/x.x").
        val base = WebSettings.getDefaultUserAgent(this)
        val mobile = base.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
        w.settings.userAgentString = if (desktop)
            mobile.replace(Regex("\\(Linux; Android [^)]*\\)"), "(X11; Linux x86_64)").replace(" Mobile", "")
        else mobile
        w.settings.useWideViewPort = true
        w.settings.loadWithOverviewMode = desktop
    }

    private fun attachClients(t: Tab) {
        t.web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                t.loading = true; t.url = url; t.progress = 5; push()
            }
            override fun onPageFinished(view: WebView, url: String) {
                t.loading = false; t.progress = 100; t.url = url; push(); saveSession()
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                t.url = url; push()
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                return when (u.scheme) {
                    "http", "https", "file", "about", "data", "blob", "javascript" -> false
                    else -> { try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}; true }
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                val u = request.url.toString()
                view.loadDataWithBaseURL(u, errorHtml(u, error.description.toString()), "text/html", "utf-8", u)
            }
        }
        t.web.webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) { t.title = title ?: ""; push() }
            override fun onProgressChanged(view: WebView, newProgress: Int) { t.progress = newProgress; push() }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val nt = newTab("", true, makeWeb())
                (resultMsg.obj as WebView.WebViewTransport).webView = nt.web
                resultMsg.sendToTarget()
                return true
            }
            override fun onCloseWindow(window: WebView) {
                tabs.firstOrNull { it.web === window }?.let { closeTab(it.id) }
            }
            override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                return try { startActivityForResult(fileChooserParams.createIntent(), 77); true }
                catch (_: Exception) { fileCallback = null; false }
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (customView != null) { callback.onCustomViewHidden(); return }
                customView = view; customCb = callback
                root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
                chrome.visibility = View.GONE; holder.visibility = View.GONE
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
            override fun onHideCustomView() { hideCustom() }
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                callback.invoke(origin, false, false)
            }
        }
    }

    private fun hideCustom() {
        val v = customView ?: return
        root.removeView(v)
        customView = null
        customCb?.onCustomViewHidden()
        customCb = null
        chrome.visibility = View.VISIBLE; holder.visibility = View.VISIBLE
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        setBars(lastBg, lastDark)
    }

    private fun errorHtml(url: String, desc: String): String {
        val bg = if (lastDark) "#000" else "#fff"
        val fg = if (lastDark) "#fff" else "#000"
        val host = Uri.parse(url).host ?: url
        val js = JSONObject.quote(url).replace("'", "\\u0027")
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return "<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<link href=\"https://fonts.googleapis.com/css2?family=Urbanist:wght@400;700&display=swap\" rel=\"stylesheet\">" +
            "<style>body{margin:0;height:100vh;display:grid;place-items:center;background:$bg;color:$fg;font-family:Urbanist,sans-serif;text-align:center}" +
            "h1{font-size:26px;margin:0 0 10px}p{color:#8c8c8c;margin:6px 24px}b{color:$fg}" +
            "button{margin-top:26px;height:44px;padding:0 28px;border:1px solid $fg;border-radius:22px;background:transparent;color:$fg;font:700 15px Urbanist,sans-serif}</style></head>" +
            "<body><div><h1>This page is unavailable</h1><p><b>${esc(host)}</b></p><p>${esc(desc)}</p>" +
            "<button onclick='location.replace($js)'>Try again</button></div></body></html>"
    }

    /* ---------- state to the interface ---------- */
    private fun push() { ui.removeCallbacks(pushRun); ui.postDelayed(pushRun, 30) }

    private fun doPush() {
        if (!ready) return
        val arr = JSONArray()
        tabs.forEach { t ->
            val u = if (t.url.startsWith("file:///android_asset")) "" else t.url
            arr.put(JSONObject().put("id", t.id).put("title", t.title).put("url", u).put("p", t.progress)
                .put("loading", t.loading).put("back", t.web.canGoBack()).put("fwd", t.web.canGoForward()))
        }
        val o = JSONObject().put("tabs", arr).put("active", activeId)
        chrome.evaluateJavascript("window.onNative&&onNative($o)", null)
    }

    private fun toast(msg: String) {
        chrome.evaluateJavascript("window.onNative&&onNative(" + JSONObject().put("toast", msg) + ")", null)
    }

    /* ---------- actions ---------- */
    private fun download(url: String, ua: String, cd: String, mime: String) {
        try {
            val name = URLUtil.guessFileName(url, cd, mime)
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("User-Agent", ua)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            toast("Downloading $name")
        } catch (_: Exception) { toast("Download failed") }
    }

    private fun share() {
        val u = active()?.url ?: return
        if (u.isBlank()) return
        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, u)
        startActivity(Intent.createChooser(i, null))
    }

    private fun clearData() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        tabs.forEach { it.web.clearCache(true) }
        toast("Cookies and cache cleared")
        active()?.web?.reload()
    }

    private fun urlFrom(i: Intent?): String? = if (i?.action == Intent.ACTION_VIEW) i?.dataString else null

    /* ---------- activity plumbing ---------- */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val u = urlFrom(intent) ?: return
        if (ready) newTab(u, true) else pending.add(u)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == 77) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (customView != null) { hideCustom(); return }
        if (full) { chrome.evaluateJavascript("window.closeOverlays&&closeOverlays()", null); return }
        val t = active()
        if (t != null && t.web.canGoBack()) { t.web.goBack(); return }
        if (tabs.size > 1) { closeTab(activeId); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onPause() {
        saveSession()
        CookieManager.getInstance().flush()
        super.onPause()
    }
}
