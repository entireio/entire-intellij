package io.entire.spikes

import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest

/**
 * Spike S1: does the panel script run unchanged in the embedded browser?
 *
 * It builds the document the plan describes (the stylesheet, the shim, and the
 * panel script inline under one nonce, with a policy that allows nothing
 * else), shows it in a tool window, and checks each direction of the bridge.
 */
class JcefSpike(private val project: Project, private val report: Report) {
    private val messages = Channel<JsonObject>(Channel.UNLIMITED)
    private val backlog = ArrayList<JsonObject>()
    private val urls = CopyOnWriteArrayList<String>()

    @Volatile
    private var blockNavigation = false
    private var probes = 0

    suspend fun run() {
        if (!JBCefApp.isSupported()) {
            report.fail("S1.supported", "JBCefApp.isSupported() is false")
            return
        }
        report.pass("S1.supported")
        val browser = withContext(Dispatchers.EDT) { JBCefBrowser.createBuilder().build() }
        try {
            drive(browser)
        } finally {
            withContext(Dispatchers.EDT) {
                ToolWindowManager.getInstance(project).unregisterToolWindow(TOOL_WINDOW)
                Disposer.dispose(browser)
            }
        }
    }

    private suspend fun drive(browser: JBCefBrowser) {
        val query = withContext(Dispatchers.EDT) {
            val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
            query.addHandler { payload ->
                try {
                    messages.trySend(Json.parseToJsonElement(payload).jsonObject)
                } catch (e: Exception) {
                    report.fail("S1.unreadableMessage", payload.take(120))
                }
                null
            }
            browser.jbCefClient.addRequestHandler(
                object : CefRequestHandlerAdapter() {
                    override fun onBeforeBrowse(
                        cefBrowser: CefBrowser?,
                        frame: CefFrame?,
                        request: CefRequest?,
                        userGesture: Boolean,
                        isRedirect: Boolean,
                    ): Boolean {
                        urls += request?.url.orEmpty()
                        return blockNavigation
                    }
                },
                browser.cefBrowser,
            )
            val toolWindow = ToolWindowManager.getInstance(project).registerToolWindow(TOOL_WINDOW) {
                anchor = ToolWindowAnchor.BOTTOM
            }
            toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(browser.component, "S1", false))
            toolWindow.show()
            query
        }

        val started = System.nanoTime()
        val document = document(query, "detail")
        withContext(Dispatchers.EDT) { browser.loadHTML(document) }
        val ready = await(30_000) { it.text("type") == "ready" }
        report.check(
            "S1.readyThroughShim",
            ready != null && ready["protocol"]?.jsonPrimitive?.intOrNull == 1,
            "after ${elapsedMs(started)} ms, message $ready, document ${document.length} chars, loaded as ${urls.firstOrNull()}",
        )
        if (ready == null) return
        blockNavigation = true

        val maxWidth = probe(browser, "return getComputedStyle(document.getElementById('app')).maxWidth")
        report.check("S1.inlineStylesheetUnderCsp", maxWidth.string() == "920px", "#app max-width is ${maxWidth.string()}")

        rx(browser, """{"type":"error","message":"The spike says hello.","canRetry":true}""")
        val errorText = probe(browser, "return document.getElementById('app').textContent")
        report.check("S1.hostToPage", errorText.string().contains("The spike says hello."), errorText.string().take(80))

        val retried = probe(
            browser,
            "var b=[].slice.call(document.querySelectorAll('button')).filter(function(x){return x.textContent==='Retry'})[0];" +
                "if(!b)return 'no Retry button';b.click();return 'clicked'",
        )
        val retry = await(5_000) { it.text("type") == "retry" }
        report.check("S1.pageToHost", retry != null, "Retry ${retried.string()}, message $retry")

        rx(browser, INIT)
        rx(browser, TURNS)
        delay(1_500)
        val page = probe(
            browser,
            "var s=document.querySelector('#app strong');return JSON.stringify({text:document.getElementById('app').textContent.slice(0,4000)," +
                "strong:s?s.textContent:null})",
        )
        val pageText = page.string()
        report.check(
            "S1.transcriptRenders",
            pageText.contains("Hello from the user") && pageText.contains("\"strong\":\"Bold\""),
            "has the prompt ${pageText.contains("Hello from the user")}, Markdown became <strong> ${pageText.contains("\"strong\":\"Bold\"")}",
        )

        execute(browser, "window.__entire.theme({'--vscode-foreground':'rgb(1, 2, 3)','--vscode-editor-background':'rgb(250, 251, 252)'});")
        val color = probe(browser, "return getComputedStyle(document.body).color")
        report.check("S1.themeThroughCssom", color.string() == "rgb(1, 2, 3)", "body color is ${color.string()}")

        val fetchId = ++probes
        execute(
            browser,
            "fetch('https://example.com/').then(function(){return 'allowed'},function(e){return 'blocked: '+e})" +
                ".then(function(v){window.__entireSend(JSON.stringify({type:'probe',id:$fetchId,value:v}))});",
        )
        val fetched = await(15_000) { it.text("type") == "probe" && it["id"]?.jsonPrimitive?.intOrNull == fetchId }
        val fetchResult = fetched?.get("value").string()
        report.check("S1.cspBlocksNetwork", fetchResult.startsWith("blocked"), fetchResult.take(100))

        execute(
            browser,
            "window.addEventListener('message',function(e){if(e.data&&e.data.type==='spikeBlob')" +
                "window.__entireSend(JSON.stringify({type:'spikeBlobAck',n:e.data.base64.length,tag:e.data.tag}))});",
        )
        val base64 = "QUJD".repeat(8 * 1024 * 1024 / 4)
        val single = System.nanoTime()
        rx(browser, """{"type":"spikeBlob","tag":"single","base64":"$base64"}""")
        val singleAck = await(30_000) { it.text("type") == "spikeBlobAck" && it.text("tag") == "single" }
        report.check(
            "S1.image8MbInOneCall",
            singleAck?.get("n")?.jsonPrimitive?.intOrNull == base64.length,
            "acknowledged ${singleAck?.get("n")} chars after ${elapsedMs(single)} ms",
        )

        val json = """{"type":"spikeBlob","tag":"chunked","base64":"$base64"}"""
        val parts = json.chunked(512 * 1024)
        val chunked = System.nanoTime()
        parts.forEachIndexed { index, part ->
            execute(browser, "window.__entire.rxChunk(1,$index,${parts.size},${JsonPrimitive(part)});")
        }
        val chunkedAck = await(30_000) { it.text("type") == "spikeBlobAck" && it.text("tag") == "chunked" }
        report.check(
            "S1.image8MbInParts",
            chunkedAck?.get("n")?.jsonPrimitive?.intOrNull == base64.length,
            "${parts.size} parts, acknowledged ${chunkedAck?.get("n")} chars after ${elapsedMs(chunked)} ms",
        )

        val before = probe(browser, "return location.href").string()
        val seen = urls.size
        execute(browser, "location.href='https://example.com/spike-navigation';")
        delay(1_500)
        val after = probe(browser, "return location.href").string()
        val asked = urls.drop(seen)
        report.check(
            "S1.navigationCancelled",
            asked.any { it.contains("spike-navigation") } && after == before,
            "handler was asked about $asked, page stayed at its document ${after == before}",
        )
    }

    private fun execute(browser: JBCefBrowser, script: String) {
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    /** Sends one message to the page the way the plugin will: as JSON text handed to the shim. */
    private fun rx(browser: JBCefBrowser, json: String) {
        execute(browser, "window.__entire.rx(${JsonPrimitive(json)});")
    }

    /** Runs a function body in the page and returns what it returns. */
    private suspend fun probe(browser: JBCefBrowser, body: String): JsonElement? {
        val id = ++probes
        execute(
            browser,
            "(function(){var out={type:'probe',id:$id};try{out.value=(function(){$body})();}catch(e){out.error=String(e);}" +
                "window.__entireSend(JSON.stringify(out));})();",
        )
        val reply = await(10_000) { it.text("type") == "probe" && it["id"]?.jsonPrimitive?.intOrNull == id } ?: return null
        reply["error"]?.let { return JsonPrimitive("ERROR " + it.string()) }
        return reply["value"]
    }

    private suspend fun await(timeoutMs: Long, match: (JsonObject) -> Boolean): JsonObject? {
        backlog.firstOrNull(match)?.let {
            backlog.remove(it)
            return it
        }
        return withTimeoutOrNull(timeoutMs) {
            var found: JsonObject? = null
            while (found == null) {
                val message = messages.receive()
                if (match(message)) found = message else backlog += message
            }
            found
        }
    }

    private fun document(query: JBCefJSQuery, view: String): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = Base64.getEncoder().encodeToString(bytes)
        val csp = "default-src 'none'; img-src data: blob:; style-src 'nonce-$nonce'; script-src 'nonce-$nonce'; " +
            "base-uri 'none'; form-action 'none'"
        // The query's function has a name of its own in each browser, so this one line cannot be built ahead of time.
        val send = "window.__entireSend=function(payload){${query.inject("payload")}};"
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="$csp">
<style nonce="$nonce">${resource("panel.css")}</style>
<title>Entire</title>
</head>
<body>
<div id="app" data-view="$view"></div>
<script nonce="$nonce">$send</script>
<script nonce="$nonce">${resource("shim.js")}</script>
<script nonce="$nonce">${resource("panel.js")}</script>
</body>
</html>"""
    }

    private fun resource(name: String): String =
        checkNotNull(JcefSpike::class.java.getResource("/webview/$name")) { "/webview/$name is not in the plugin" }.readText()

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.string(): String = (this as? JsonPrimitive)?.contentOrNull ?: this?.toString() ?: "null"

    private companion object {
        const val TOOL_WINDOW = "Entire Spike"

        const val INIT = """{"type":"init","protocol":1,"header":{"mode":"checkpoint","title":"Spike checkpoint","checkpointId":"01HZXK3M5N7P9R2T4V6W8Y0A1B","otherCommits":0,"sessions":[{"index":0,"agent":"Claude Code"}],"selected":0,"files":[],"canOpenOnWeb":false,"canShowCommitDiff":false,"canOpenRaw":false},"transcript":{"source":"compact","total":2,"start":0},"restore":{"repoRoot":"/spike","mode":"checkpoint","checkpointId":"01HZXK3M5N7P9R2T4V6W8Y0A1B"}}"""

        const val TURNS = """{"type":"turns","turns":[{"index":0,"role":"user","blocks":[{"kind":"text","text":"Hello from the user"}]},{"index":1,"role":"assistant","blocks":[{"kind":"text","text":"A **Bold** answer."}]}],"done":true}"""
    }
}
