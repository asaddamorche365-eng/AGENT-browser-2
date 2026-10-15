package com.zenpx.zbrowser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Canvas
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask

class MainActivity : Activity() {

    class Tab(val id: Int, val web: WebView, val ext: Boolean) { var loading = false; var desktop = false }

    private val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    private val prefs by lazy { getSharedPreferences("zb", MODE_PRIVATE) }
    private val nativeWait = ConcurrentHashMap<String, (String) -> Unit>()
    @Volatile private var agentGen = 0
    private var agentOffset = 0L
    private val EXT_ID = "abcdefghijklmnopabcdefghijklmnop"

    private val tabs = LinkedHashMap<Int, Tab>()
    private var nextId = 1
    private var current = 1
    private var lastPage = 1
    private lateinit var bg: WebView
    private lateinit var frame: FrameLayout
    private lateinit var barRow: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(4)
    private val pending = ConcurrentHashMap<String, WebView>()
    private val store = JSONObject()
    private val lock = Any()
    private var saving = false
    private var manifest = JSONObject()
    private val shim by lazy { assets.open("zex_shim.js").bufferedReader().readText() }
    private val resScript by lazy { buildRes() }

    // ---------- helpers ----------
    private fun q(s: String): String = JSONObject.quote(s)
    private fun js(w: WebView, code: String) { ui.post { w.evaluateJavascript(code, null) } }
    private fun <T> onUi(f: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return f()
        val t = FutureTask { f() }
        ui.post(t)
        return t.get()
    }
    private fun asset(p: String): ByteArray? = try { assets.open("zex/$p").readBytes() } catch (e: Exception) { null }

    private fun buildRes(): String {
        val res = JSONObject()
        val mimes = mapOf("ttf" to "font/ttf", "jpg" to "image/jpeg", "png" to "image/png")
        for (p in listOf("fonts/zex_pixel.ttf", "icons/agent_avatar.jpg", "icons/logo.png",
            "icons/icon16.png", "icons/icon48.png", "icons/icon128.png")) {
            val b = asset(p) ?: continue
            res.put(p, "data:${mimes[p.substringAfterLast('.')]};base64," + Base64.encodeToString(b, Base64.NO_WRAP))
        }
        asset("style.css")?.let { res.put("style.css", String(it)) }
        return "window.__ZEX_RES=$res;"
    }

    private fun persist() {
        if (saving) return
        saving = true
        ui.postDelayed({
            saving = false
            val s = synchronized(lock) { store.toString() }
            pool.execute { try { File(filesDir, "zex_store.json").writeText(s) } catch (e: Exception) {} }
        }, 800)
    }

    private fun broadcastStorage(ch: JSONObject, except: WebView?) {
        val code = "window.__zexStorageChanged&&window.__zexStorageChanged($ch)"
        ui.post {
            if (bg !== except) bg.evaluateJavascript(code, null)
            for (t in tabs.values) if (t.web !== except) t.web.evaluateJavascript(code, null)
        }
    }

    private fun matchPat(p: String, url: String): Boolean {
        if (p == "<all_urls>") return true
        val re = Regex("^" + p.split("*").joinToString(".*") { Regex.escape(it) } + "$")
        return re.matches(url)
    }

    private fun tabJson(t: Tab): JSONObject {
        val active = t.id == (if (tabs[current]?.ext == true) lastPage else current)
        return JSONObject().put("id", t.id).put("url", t.web.url ?: "").put("title", t.web.title ?: "")
            .put("active", active).put("status", if (t.loading) "loading" else "complete")
            .put("windowId", 1).put("index", tabs.keys.indexOf(t.id)).put("pinned", false)
            .put("incognito", false).put("highlighted", active)
    }

    private fun dispatch(w: WebView, id: String, msg: String, sender: String, reply: Boolean) {
        js(w, "window.__zexDispatch&&window.__zexDispatch(${q(id)},${q(msg)},${q(sender)},$reply)")
    }

    // ---------- serving extension files at https://zex.local/ ----------
    private fun mimeOf(p: String) = when (p.substringAfterLast('.', "").lowercase()) {
        "js" -> "application/javascript"; "css" -> "text/css"; "html" -> "text/html"
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "ttf" -> "font/ttf"
        "json" -> "application/json"; "svg" -> "image/svg+xml"; else -> "application/octet-stream"
    }

    private fun shimTag() = "<script>" + (resScript + "\n" + shim).replace("</", "<\\/") + "</script>"

    private fun serve(path: String): WebResourceResponse {
        val p = path.trimStart('/')
        val hdr = mapOf("Access-Control-Allow-Origin" to "*")
        if (p == "__bg.html") {
            val h = "<!doctype html><html><head><meta charset=utf-8>${shimTag()}</head><body></body></html>"
            return WebResourceResponse("text/html", "utf-8", 200, "OK", hdr, ByteArrayInputStream(h.toByteArray()))
        }
        var data = asset(p) ?: return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", hdr, ByteArrayInputStream(ByteArray(0)))
        if (p.endsWith(".html")) {
            val s = String(data)
            val m = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(s)
            val at = m?.range?.last?.plus(1) ?: 0
            data = (s.substring(0, at) + shimTag() + s.substring(at)).toByteArray()
        }
        return WebResourceResponse(mimeOf(p), "utf-8", 200, "OK", hdr, ByteArrayInputStream(data))
    }

    // ---------- content script injection ----------
    private fun inject(view: WebView, url: String, early: Boolean) {
        @Suppress("UNCHECKED_CAST")
        val done = (view.tag as? HashSet<String>) ?: HashSet<String>().also { view.tag = it }
        if (early) done.clear()
        if (!early && done.add("__shim")) js(view, resScript + "\n" + shim)
        if (early) js(view, resScript + "\n" + shim)
        val blocks = manifest.optJSONArray("content_scripts") ?: return
        for (i in 0 until blocks.length()) {
            val b = blocks.getJSONObject(i)
            val isStart = b.optString("run_at", "document_idle") == "document_start"
            if (isStart != early) continue
            val ms = b.getJSONArray("matches")
            var ok = false
            for (k in 0 until ms.length()) if (matchPat(ms.getString(k), url)) ok = true
            if (!ok) continue
            b.optJSONArray("css")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val f = arr.getString(k)
                    if (early || done.add("css:$f")) asset(f)?.let { bytes ->
                        js(view, "(function(){var s=document.createElement('style');s.textContent=${q(String(bytes))};(document.head||document.documentElement).appendChild(s)})()")
                    }
                }
            }
            b.optJSONArray("js")?.let { arr ->
                for (k in 0 until arr.length()) {
                    val f = arr.getString(k)
                    if (early || done.add(f)) asset(f)?.let { js(view, String(it)) }
                }
            }
        }
    }

    // ---------- bridge exposed to every WebView as ZBridge ----------
    inner class Bridge(val web: WebView, val role: String, val tid: Int) {
        @JavascriptInterface fun role(): String = role
        @JavascriptInterface fun tabId(): Int = tid
        @JavascriptInterface fun readAsset(p: String): String = asset(p)?.let { String(it) } ?: ""
        @JavascriptInterface fun toast(m: String) { ui.post { Toast.makeText(this@MainActivity, m, Toast.LENGTH_SHORT).show() }; agentSend("🔔 $m") }

        // storage
        @JavascriptInterface fun storageAll(): String = synchronized(lock) { store.toString() }
        @JavascriptInterface fun storageSet(json: String) {
            val o = JSONObject(json)
            val ch = JSONObject()
            synchronized(lock) {
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val c = JSONObject()
                    if (store.has(k)) c.put("oldValue", store.get(k))
                    c.put("newValue", o.get(k))
                    ch.put(k, c)
                    store.put(k, o.get(k))
                }
            }
            persist(); broadcastStorage(ch, web)
        }
        @JavascriptInterface fun storageRemove(keysJson: String) {
            val arr = JSONArray(keysJson)
            val ch = JSONObject()
            synchronized(lock) {
                for (i in 0 until arr.length()) {
                    val k = arr.getString(i)
                    if (store.has(k)) { ch.put(k, JSONObject().put("oldValue", store.get(k))); store.remove(k) }
                }
            }
            persist(); broadcastStorage(ch, web)
        }
        @JavascriptInterface fun storageClear() {
            val ch = JSONObject()
            synchronized(lock) {
                val ks = store.keys().asSequence().toList()
                for (k in ks) { ch.put(k, JSONObject().put("oldValue", store.get(k))); store.remove(k) }
            }
            persist(); broadcastStorage(ch, web)
        }

        // messaging
        private fun senderJson(): String {
            val o = JSONObject().put("id", EXT_ID)
            if (role == "content") {
                val u = onUi { tabs[tid]?.web?.url ?: "" }
                o.put("url", u).put("tab", JSONObject().put("id", tid).put("url", u).put("windowId", 1))
            } else o.put("url", "https://zex.local/popup.html")
            return o.toString()
        }
        @JavascriptInterface fun send(id: String, msg: String) {
            val sender = senderJson()
            if (role == "bg") {
                ui.post { for (t in tabs.values) if (t.ext) dispatch(t.web, id, msg, sender, false) }
            } else {
                pending[id] = web
                ui.post {
                    dispatch(bg, id, msg, sender, true)
                    if (role == "content") for (t in tabs.values) if (t.ext) dispatch(t.web, "$id-x", msg, sender, false)
                }
            }
        }
        @JavascriptInterface fun reply(id: String, resp: String) {
            nativeWait.remove(id)?.let { it(resp); return }
            val w = pending.remove(id) ?: return
            js(w, "window.__zexReply(${q(id)},${q(resp)})")
        }
        @JavascriptInterface fun tabsSend(id: String, tabId: Int, msg: String) {
            val t = onUi { tabs[tabId] }
            if (t == null) { js(web, "window.__zexReply(${q(id)},'null')"); return }
            pending[id] = web
            val sender = JSONObject().put("id", EXT_ID).put("url", "https://zex.local/__bg.html").toString()
            ui.post { dispatch(t.web, id, msg, sender, true) }
        }
        @JavascriptInterface fun exec(id: String, tabId: Int, code: String) {
            val t = onUi { tabs[tabId] }
            if (t == null) { js(web, "window.__zexReply(${q(id)},'[]')"); return }
            pending[id] = web
            js(t.web, code)
        }

        // tabs
        @JavascriptInterface fun tabsCreate(url: String, active: Boolean): String = onUi { tabJson(createTab(url, active)).toString() }
        @JavascriptInterface fun tabsRemove(id: Int) { ui.post { removeTab(id) } }
        @JavascriptInterface fun tabsUpdate(id: Int, url: String, active: Int): String = onUi {
            val t = tabs[id] ?: return@onUi ""
            if (url.isNotEmpty()) t.web.loadUrl(url)
            if (active == 1) show(id)
            tabJson(t).toString()
        }
        @JavascriptInterface fun tabsReload(id: Int) { ui.post { tabs[id]?.web?.reload() } }
        @JavascriptInterface fun tabsGet(id: Int): String = onUi { tabs[id]?.let { tabJson(it).toString() } ?: "" }
        @JavascriptInterface fun tabsQuery(): String = onUi {
            val a = JSONArray(); for (t in tabs.values) a.put(tabJson(t)); a.toString()
        }

        // network (no CORS)
        @JavascriptInterface fun fetch(id: String, method: String, url: String, headersJson: String, body: String) {
            pool.execute {
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.requestMethod = method
                    c.instanceFollowRedirects = true
                    c.connectTimeout = 20000; c.readTimeout = 90000
                    val h = JSONObject(if (headersJson.isBlank()) "{}" else headersJson)
                    val ks = h.keys()
                    while (ks.hasNext()) { val k = ks.next(); try { c.setRequestProperty(k, h.getString(k)) } catch (e: Exception) {} }
                    if (body.isNotEmpty() && method != "GET" && method != "HEAD") {
                        c.doOutput = true
                        c.outputStream.use { it.write(body.toByteArray()) }
                    }
                    val code = c.responseCode
                    val st = if (code >= 400) c.errorStream else c.inputStream
                    val bytes = st?.readBytes() ?: ByteArray(0)
                    val hd = JSONObject()
                    for ((k, v) in c.headerFields) if (k != null) hd.put(k, v.joinToString(", "))
                    js(web, "window.__zexFetchDone(${q(id)},$code,${q(hd.toString())},${q(Base64.encodeToString(bytes, Base64.NO_WRAP))})")
                } catch (e: Exception) {
                    js(web, "window.__zexFetchDone(${q(id)},0,'{}','')")
                }
            }
        }
    }

    // ---------- tabs / UI ----------
    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWebView(role: String, tid: Int): WebView {
        val w = WebView(this)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = UA
            javaScriptCanOpenWindowsAutomatically = true
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(w, true) }
        w.addJavascriptInterface(Bridge(w, role, tid), "ZBridge")
        w.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                android.util.Log.d("ZEX-$role$tid", m.message()); return true
            }
        }
        w.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                if (r.url.host == "zex.local") serve(r.url.path ?: "") else null

            override fun onPageStarted(v: WebView, url: String, f: Bitmap?) {
                if (role != "content") return
                tabs[tid]?.loading = true
                inject(v, url, true)
                js(bg, "window.__zexTabUpdated&&window.__zexTabUpdated($tid,{\"status\":\"loading\",\"url\":${q(url)}},${tabs[tid]?.let { tabJson(it) } ?: "{}"})")
            }

            override fun onPageFinished(v: WebView, url: String) {
                if (role == "bg") { v.evaluateJavascript("window.__zexBootBg&&window.__zexBootBg()", null); return }
                if (role != "content") return
                tabs[tid]?.loading = false
                inject(v, url, false)
                js(bg, "window.__zexTabUpdated&&window.__zexTabUpdated($tid,{\"status\":\"complete\",\"url\":${q(url)}},${tabs[tid]?.let { tabJson(it) } ?: "{}"})")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = true
        }
        return w
    }

    private fun createTab(url: String, active: Boolean): Tab {
        val id = nextId++
        val ext = Uri.parse(url).host == "zex.local"
        val w = makeWebView(if (ext) "ext" else "content", id)
        w.visibility = View.INVISIBLE
        frame.addView(w, FrameLayout.LayoutParams(-1, -1))
        val t = Tab(id, w, ext)
        t.desktop = prefs.getBoolean("desk$id", false)
        applyMode(t)
        tabs[id] = t
        w.loadUrl(url)
        if (active) show(id) else rebuildBar()
        return t
    }

    private fun removeTab(id: Int) {
        if (id <= 3) return
        val t = tabs.remove(id) ?: return
        frame.removeView(t.web)
        t.web.destroy()
        js(bg, "window.__zexTabRemoved&&window.__zexTabRemoved($id)")
        if (current == id) show(lastPage) else rebuildBar()
    }

    private fun show(id: Int) {
        val t = tabs[id] ?: return
        current = id
        if (!t.ext) lastPage = id
        for (x in tabs.values) x.web.visibility = if (x.id == id) View.VISIBLE else View.INVISIBLE
        rebuildBar()
    }

    private fun rebuildBar() {
        barRow.removeAllViews()
        for (t in tabs.values) {
            val label = when (t.id) { 1 -> "X"; 2 -> "TG"; 3 -> "Panel"; else -> "T${t.id}" }
            barRow.addView(Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setBackgroundColor(if (t.id == current) Color.parseColor("#1d9bf0") else Color.TRANSPARENT)
                minWidth = 0; minimumWidth = 0
                setOnClickListener { show(t.id) }
                setOnLongClickListener { removeTab(t.id); true }
            })
        }
        barRow.addView(Button(this).apply {
            text = "🖥"
            setTextColor(Color.WHITE)
            setBackgroundColor(if (tabs[current]?.desktop == true) Color.parseColor("#00ba7c") else Color.TRANSPARENT)
            minWidth = 0; minimumWidth = 0
            setOnClickListener { toggleDesktop(current) }
        })
        barRow.addView(Button(this).apply {
            text = "⚙"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0; minimumWidth = 0
            setOnClickListener { agentDialog() }
        })
        barRow.addView(Button(this).apply {
            text = "↻"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            minWidth = 0; minimumWidth = 0
            setOnClickListener { tabs[current]?.web?.reload() }
        })
    }

    // ---------- desktop mode ----------
    private fun applyMode(t: Tab) {
        t.web.settings.apply {
            userAgentString = if (t.desktop) DESKTOP_UA else UA
            useWideViewPort = t.desktop
            loadWithOverviewMode = t.desktop
            setSupportZoom(true)
            builtInZoomControls = t.desktop
            displayZoomControls = false
        }
    }

    private fun toggleDesktop(id: Int) {
        val t = tabs[id] ?: return
        t.desktop = !t.desktop
        prefs.edit().putBoolean("desk$id", t.desktop).apply()
        applyMode(t)
        t.web.reload()
        rebuildBar()
        Toast.makeText(this, if (t.desktop) "Desktop mode ON" else "Mobile mode", Toast.LENGTH_SHORT).show()
    }

    // ---------- remote agent (Telegram Bot) ----------
    private fun tgCall(method: String, params: Map<String, String>): String {
        val token = prefs.getString("tg_token", "") ?: ""
        val c = URL("https://api.telegram.org/bot$token/$method").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true
        c.connectTimeout = 15000; c.readTimeout = 40000
        val body = params.entries.joinToString("&") { URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8") }
        c.outputStream.use { it.write(body.toByteArray()) }
        val st = if (c.responseCode >= 400) c.errorStream else c.inputStream
        return st.bufferedReader().readText()
    }

    private fun agentSend(text: String) {
        val chat = prefs.getString("tg_chat", "") ?: ""
        if (chat.isEmpty() || (prefs.getString("tg_token", "") ?: "").isEmpty()) return
        pool.execute {
            try {
                var t = text
                while (t.isNotEmpty()) {
                    tgCall("sendMessage", mapOf("chat_id" to chat, "text" to t.take(4000)))
                    t = t.drop(4000)
                }
            } catch (e: Exception) {}
        }
    }

    private fun agentPhoto(bytes: ByteArray, caption: String) {
        val chat = prefs.getString("tg_chat", "") ?: ""
        val token = prefs.getString("tg_token", "") ?: ""
        if (chat.isEmpty() || token.isEmpty()) return
        pool.execute {
            try {
                val b = "----zb" + System.currentTimeMillis()
                val c = URL("https://api.telegram.org/bot$token/sendPhoto").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
                c.outputStream.use { o ->
                    fun field(n: String, v: String) { o.write("--$b\r\nContent-Disposition: form-data; name=\"$n\"\r\n\r\n$v\r\n".toByteArray()) }
                    field("chat_id", chat); field("caption", caption)
                    o.write("--$b\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"s.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
                    o.write(bytes)
                    o.write("\r\n--$b--\r\n".toByteArray())
                }
                c.responseCode
            } catch (e: Exception) {}
        }
    }

    private fun startAgent() {
        val gen = ++agentGen
        if ((prefs.getString("tg_token", "") ?: "").isEmpty()) return
        agentSend("🟢 Z Browser online")
        Thread {
            while (gen == agentGen) {
                try {
                    val r = JSONObject(tgCall("getUpdates", mapOf("timeout" to "25", "offset" to agentOffset.toString())))
                    if (!r.optBoolean("ok")) { Thread.sleep(8000); continue }
                    val arr = r.optJSONArray("result") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val u = arr.getJSONObject(i)
                        agentOffset = u.getLong("update_id") + 1
                        val m = u.optJSONObject("message") ?: continue
                        val chat = m.getJSONObject("chat").getLong("id").toString()
                        val text = m.optString("text", "").trim()
                        var owner = prefs.getString("tg_chat", "") ?: ""
                        if (owner.isEmpty() && text.startsWith("/start")) {
                            owner = chat; prefs.edit().putString("tg_chat", chat).apply()
                        }
                        if (chat != owner) continue
                        handleCmd(text)
                    }
                } catch (e: Exception) { try { Thread.sleep(5000) } catch (x: Exception) {} }
            }
        }.start()
    }

    private fun handleCmd(text: String) {
        val cmd = text.substringBefore(' ').substringBefore('@').lowercase()
        val arg = text.substringAfter(' ', "").trim()
        when (cmd) {
            "/start", "/help" -> agentSend("Z Agent ready.\n/status\n/tab <n>\n/open <url>\n/search <keyword>\n/reload\n/back\n/desktop\n/shot\n/eval <js>\n/msg <json>")
            "/status", "/tabs" -> agentSend(onUi {
                tabs.values.joinToString("\n") { "${it.id}${if (it.id == current) "*" else ""}${if (it.desktop) " [D]" else ""} ${it.web.url}" }
            })
            "/tab" -> { ui.post { arg.toIntOrNull()?.let { show(it) } }; agentSend("ok") }
            "/open" -> {
                val u = if (arg.startsWith("http")) arg else "https://$arg"
                ui.post { (tabs[current]?.takeIf { !it.ext } ?: tabs[1])?.web?.loadUrl(u) }
                agentSend("opening $u")
            }
            "/search" -> {
                ui.post { tabs[1]?.web?.loadUrl("https://x.com/search?q=" + URLEncoder.encode(arg, "UTF-8") + "&f=live") }
                agentSend("searching: $arg")
            }
            "/reload" -> { ui.post { tabs[current]?.web?.reload() }; agentSend("ok") }
            "/back" -> { ui.post { tabs[current]?.web?.goBack() }; agentSend("ok") }
            "/desktop" -> { ui.post { toggleDesktop(current) }; agentSend("toggled") }
            "/shot" -> ui.post {
                val w = tabs[current]?.web
                if (w == null || w.width <= 0) { agentSend("no view"); return@post }
                val bmp = Bitmap.createBitmap(w.width, w.height, Bitmap.Config.ARGB_8888)
                w.draw(Canvas(bmp))
                pool.execute {
                    val o = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, o)
                    agentPhoto(o.toByteArray(), w.url ?: "")
                }
            }
            "/eval" -> ui.post {
                tabs[current]?.web?.evaluateJavascript(arg) { agentSend("→ $it") }
            }
            "/msg" -> {
                val id = "agent-" + System.nanoTime()
                nativeWait[id] = { agentSend("← $it") }
                val sender = JSONObject().put("id", EXT_ID).put("url", "https://zex.local/popup.html").toString()
                ui.post { dispatch(bg, id, arg, sender, true) }
                pool.execute { Thread.sleep(30000); if (nativeWait.remove(id) != null) agentSend("timeout") }
            }
            else -> agentSend("? /help")
        }
    }

    private fun agentDialog() {
        val ll = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 0) }
        val t = EditText(this).apply { hint = "Bot token (BotFather theke)"; setText(prefs.getString("tg_token", "")) }
        val c = EditText(this).apply { hint = "Chat ID (khali rakho, bot-e /start dile auto)"; setText(prefs.getString("tg_chat", "")) }
        ll.addView(t); ll.addView(c)
        AlertDialog.Builder(this)
            .setTitle("Remote Agent (Telegram Bot)")
            .setView(ll)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit().putString("tg_token", t.text.toString().trim()).putString("tg_chat", c.text.toString().trim()).apply()
                agentOffset = 0L
                startAgent()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { manifest = JSONObject(String(asset("manifest.json") ?: "{}".toByteArray())) } catch (e: Exception) {}
        try {
            val f = File(filesDir, "zex_store.json")
            if (f.exists()) { val o = JSONObject(f.readText()); val it = o.keys(); while (it.hasNext()) { val k = it.next(); store.put(k, o.get(k)) } }
        } catch (e: Exception) {}

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        barRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scroll = HorizontalScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            addView(barRow)
        }
        frame = FrameLayout(this)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        bg = makeWebView("bg", 0)
        frame.addView(bg, FrameLayout.LayoutParams(1, 1))
        bg.loadUrl("https://zex.local/__bg.html")

        createTab("https://x.com", true)                    // 1
        createTab("https://web.telegram.org/k/", false)      // 2
        createTab("https://zex.local/popup.html", false)     // 3 (extension panel)
        show(1)
        startAgent()
    }

    override fun onDestroy() { agentGen++; super.onDestroy() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = tabs[current]?.web
        if (w != null && w.canGoBack()) w.goBack() else super.onBackPressed()
    }
}
