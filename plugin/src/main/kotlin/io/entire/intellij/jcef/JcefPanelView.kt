package io.entire.intellij.jcef

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import io.entire.core.json.parseJson
import io.entire.core.json.stringOrNull
import io.entire.core.json.stringify
import io.entire.intellij.editor.FallbackPanelView
import io.entire.intellij.editor.PAGE_DETAIL
import io.entire.intellij.editor.PAGE_TRAIL
import io.entire.intellij.editor.PanelView
import io.entire.intellij.editor.PanelViewFactory
import java.security.SecureRandom
import java.util.Base64
import javax.swing.JComponent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest

/** Registered only when the IDE has the embedded browser. */
class JcefPanelViewFactory : PanelViewFactory {
    override fun create(project: Project, parent: Disposable, page: String, onMessage: (JsonElement?) -> Unit, onReset: () -> Unit): PanelView =
        // The browser can be present and still not usable, for instance in an IDE started with another Java runtime.
        if (JBCefApp.isSupported()) JcefPanelView(parent, page, onMessage, onReset) else FallbackPanelView()
}

/**
 * A panel's page in the embedded browser.
 *
 * The page is one document with everything inline under one nonce: the base
 * stylesheet, the panel's stylesheet, a line that carries messages to the
 * IDE, the shim, and the panel script. Its policy allows nothing else, so the
 * page cannot load or send anything by address.
 */
class JcefPanelView(parent: Disposable, private val page: String, private val onMessage: (JsonElement?) -> Unit, onReset: () -> Unit) : PanelView {
    private val log = logger<JcefPanelView>()
    private val browser: JBCefBrowser = JBCefBrowser.createBuilder().build()

    // The query has to exist before the browser loads anything.
    private val query: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    /** True once the first document is in. Any navigation after that is refused. */
    @Volatile
    private var loaded = false

    /** True once the page's script has run and asked for data. */
    @Volatile
    private var ready = false

    override val component: JComponent get() = browser.component

    init {
        Disposer.register(parent, this)
        Disposer.register(this, browser)
        Disposer.register(this, query)

        query.addHandler { payload ->
            val message = parseJson(payload)
            if ((message as? JsonObject)?.get("type").stringOrNull() == "ready") {
                ready = true
                loaded = true
                // The page asks for the theme by being ready, and gets it before its data.
                applyTheme()
            }
            onMessage(message)
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
                    // The page opens links by asking the IDE. It never goes anywhere itself.
                    if (loaded) log.info("Refused a navigation of the panel page.")
                    return loaded
                }
            },
            browser.cefBrowser,
        )

        val connection = ApplicationManager.getApplication().messageBus.connect(this)
        connection.subscribe(LafManagerListener.TOPIC, LafManagerListener { applyTheme() })
        connection.subscribe(EditorColorsManager.TOPIC, EditorColorsListener { applyTheme() })

        onReset()
        browser.loadHTML(document())
    }

    override fun post(message: JsonObject) {
        if (!ready) return
        run("window.__entire.rx(${JsonPrimitive(stringify(message))});")
    }

    override fun readText(onText: (String) -> Unit) {
        browser.cefBrowser.getText { onText(it.orEmpty()) }
    }

    private fun applyTheme() {
        if (!ready) return
        run("window.__entire.theme(${stringify(PanelTheme.json())});")
    }

    private fun run(script: String) {
        browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    private fun document(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val nonce = Base64.getEncoder().encodeToString(bytes)
        val csp = "default-src 'none'; img-src data: blob:; style-src 'nonce-$nonce'; script-src 'nonce-$nonce'; " +
            "base-uri 'none'; form-action 'none'"
        // The query's function has a name of its own in each browser, so this one line cannot be built ahead of time.
        val send = "window.__entireSend=function(payload){${query.inject("payload")}};"
        // The colors are set before the first paint, so the page does not flash white in a dark theme.
        val colors = PanelTheme.variables().entries.joinToString("") { "${it.key}:${it.value};" }
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="$csp">
<style nonce="$nonce">:root{$colors}</style>
<style nonce="$nonce">${resource("host.css")}</style>
<style nonce="$nonce">${resource("panel.css")}</style>
<title>Entire</title>
</head>
<body>
<div id="app" data-view="${if (page == PAGE_TRAIL) PAGE_TRAIL else PAGE_DETAIL}"></div>
<script nonce="$nonce">$send</script>
<script nonce="$nonce">${resource("shim.js")}</script>
<script nonce="$nonce">${resource("panel.js")}</script>
</body>
</html>"""
    }

    private fun resource(name: String): String =
        checkNotNull(JcefPanelView::class.java.getResource("/webview/$name")) { "/webview/$name is not in the plugin" }.readText()

    override fun dispose() {}
}
