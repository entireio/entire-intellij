package io.entire.intellij.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBPanelWithEmptyText
import io.entire.intellij.EntireBundle
import javax.swing.JComponent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** What draws a panel inside an editor tab, and sends back what the reader does in it. */
interface PanelView : Disposable {
    val component: JComponent

    /** Sends one message to the page. A view with no page drops it. */
    fun post(message: JsonObject)

    /** The page's text as a reader sees it, for a check that cannot look at the page. Empty for a view with no page. */
    fun readText(onText: (String) -> Unit) = onText("")
}

/**
 * Makes the view that needs the embedded browser. The plugin registers one
 * only when the IDE has the browser, so its absence is how the plugin knows.
 */
interface PanelViewFactory {
    /**
     * `page` names the page to draw: a checkpoint or session, or a trail.
     * `onMessage` gets each message from the page. `onReset` is called when
     * the page is loaded again and has to be told everything anew.
     */
    fun create(project: Project, parent: Disposable, page: String, onMessage: (JsonElement?) -> Unit, onReset: () -> Unit): PanelView

    companion object {
        fun getInstance(): PanelViewFactory? = ApplicationManager.getApplication().getService(PanelViewFactory::class.java)
    }
}

/** What a tab shows in an IDE that has no embedded browser: why there is no transcript to read. */
class FallbackPanelView : PanelView {
    override val component: JComponent = JBPanelWithEmptyText().also {
        it.emptyText.appendLine(EntireBundle.message("panel.fallback.1"))
        it.emptyText.appendLine(EntireBundle.message("panel.fallback.2"))
    }

    override fun post(message: JsonObject) {}

    override fun dispose() {}
}
