package io.entire.intellij.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.tabs.JBTabs
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import io.entire.intellij.EntireBundle
import io.entire.intellij.EntireService
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The tabs of the Entire tool window, on a row of their own under the title
 * bar.
 *
 * The tool window sits at the side of the IDE, where it is narrow. In the
 * title bar the tabs share that width with the tool window's name and its
 * buttons, and the ones that do not fit go into a menu. On their own row all
 * four fit, and the title bar keeps the buttons.
 *
 * The Trails tab exists while a repository that Entire hosts is open and
 * trails are turned on. The Brain tab exists once the repositories have been
 * read and Entire is enabled in one. The Setup tab comes first while the CLI
 * is missing or too old or Entire is not enabled, and whenever it is asked
 * for, until it is closed.
 */
class EntireTabs(project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val service = EntireService.getInstance(project)
    private val tabs: JBTabs = JBTabsFactory.createTabs(project, parent)
    private val setupPanel = SetupPanel(project, parent)
    private val setup = tab(setupPanel, "tab.setup")
    private val checkpoints = tab(RowsPanel(project, parent, RowsTab.CHECKPOINTS), "tab.checkpoints")
    private val sessions = tab(RowsPanel(project, parent, RowsTab.SESSIONS), "tab.sessions")
    private val trails = tab(RowsPanel(project, parent, RowsTab.TRAILS), "tab.trails")
    private val brain = tab(BrainPanel(project, parent), "tab.brain")

    /** Each tab by the name it has in every language: the one a check asks for. */
    private val byName = linkedMapOf("Setup" to setup, "Checkpoints" to checkpoints, "Sessions" to sessions, "Trails" to trails, "Brain" to brain)

    /** The Setup tab was asked for, and has not been closed since. */
    private var setupAsked = false

    /** The Setup tab was closed while it was needed. It stays closed until it is asked for again. */
    private var setupClosed = false

    /** The last ask for the Setup tab that was answered. */
    private var asksAnswered = 0

    init {
        // The tabs have a fixed order, and none of them closes.
        tabs.presentation.setTabDraggingEnabled(false)
        arrange(listOf(checkpoints, sessions))
        add(tabs.component, BorderLayout.CENTER)

        setupPanel.onClose = {
            setupAsked = false
            setupClosed = true
            arrange()
        }
        // What is set up is read each time the Setup tab is chosen, and not before.
        tabs.addListener(
            object : TabsListener {
                override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                    if (newSelection === setup && oldSelection !== setup) setupPanel.check()
                }
            },
            parent,
        )

        // The tab's name says how many sessions are running.
        service.observe(parent, service.sessionRows) { view ->
            sessions.setText(if (view.badge > 0) EntireBundle.message("tab.sessions.running", view.badge) else EntireBundle.message("tab.sessions"))
        }
        service.observe(parent, service.chrome) {
            arrange()
            // What the Setup tab says depends on what the project was found to have, so it reads again when that changes.
            if (tabs.selectedInfo === setup) setupPanel.check()
        }
        service.observe(parent, service.setupRequests) { ask ->
            if (ask.count <= asksAnswered) return@observe
            asksAnswered = ask.count
            setupAsked = true
            setupClosed = false
            arrange()
            tabs.select(setup, true)
            setupPanel.focus(ask.step)
        }
    }

    /** Puts on show the tabs that the project's state calls for. */
    private fun arrange() {
        val chrome = service.chrome.value
        val needed = chrome.setupNeeded && !setupClosed
        val firstNeed = needed && setup !in tabs.tabs
        // Once it has come up by itself, the tab stays until it is closed: the steps after Enable are still to take.
        if (needed) setupAsked = true
        arrange(
            listOfNotNull(
                setup.takeIf { needed || setupAsked },
                checkpoints,
                sessions,
                trails.takeIf { chrome.hasTrails },
                brain.takeIf { chrome.ready },
            ),
        )
        // A project that needs setting up opens on the Setup tab.
        if (firstNeed) tabs.select(setup, false)
    }

    /**
     * Puts these tabs on show, in this order. A tab that comes back is put
     * where it belongs, and not where the tab strip last had it. A tab that
     * goes away while it is selected hands over to the first one.
     */
    private fun arrange(wanted: List<TabInfo>) {
        for (info in byName.values) {
            if (info !in wanted && info in tabs.tabs) tabs.removeTab(info)
        }
        wanted.forEachIndexed { index, info ->
            if (info !in tabs.tabs) tabs.addTab(info, index)
        }
        if (tabs.selectedInfo.let { it == null || it !in wanted }) tabs.select(wanted.first(), false)
    }

    private fun tab(panel: JComponent, key: String): TabInfo = TabInfo(panel).setText(EntireBundle.message(key))

    /** The tabs on show, by name, in the order they are drawn. */
    fun names(): List<String> = tabs.tabs.mapNotNull { info -> byName.entries.firstOrNull { it.value === info }?.key }

    /** What a tab holds, or null for a tab that is not on show. */
    fun panel(name: String): JComponent? = byName[name]?.takeIf { it in tabs.tabs }?.component

    /** What a tab's label reads, which for the Sessions tab includes how many are running. */
    fun title(name: String): String = byName[name]?.text.orEmpty()

    /** The name of the selected tab. */
    fun selected(): String? = byName.entries.firstOrNull { it.value === tabs.selectedInfo }?.key

    /** Selects a tab. Returns false for a tab that is not on show. */
    fun select(name: String): Boolean {
        val wanted = byName[name]?.takeIf { it in tabs.tabs } ?: return false
        tabs.select(wanted, false)
        return true
    }
}
