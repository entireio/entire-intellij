package io.entire.intellij.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.PopupHandler
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import com.intellij.util.ui.update.UiNotifyConnector
import io.entire.app.repo.ViewState
import io.entire.app.rows.EmptyLink
import io.entire.app.rows.Hover
import io.entire.app.rows.filterRows
import io.entire.app.setup.StepId
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.ActionLink
import javax.swing.event.DocumentEvent
import io.entire.app.rows.FollowedView
import io.entire.app.rows.OnOpen
import io.entire.app.rows.RowKind
import io.entire.app.rows.RowNode
import io.entire.app.rows.RowsView
import io.entire.app.rows.TrailIcon
import io.entire.app.rows.TrailRow
import io.entire.core.protocol.TrailTarget
import io.entire.core.trails.TrailFilter
import io.entire.core.trails.TrailListStatus
import io.entire.core.trails.trailFilterKey
import io.entire.core.trails.trailFilterLabel
import io.entire.core.trails.words
import io.entire.intellij.EntireBundle
import io.entire.intellij.EntireService
import io.entire.intellij.settings.EntireSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.ToolTipManager
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeExpansionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The tabs that list rows. A tab's name is also how a detail tab remembers
 * where it was opened from, for the two tabs whose rows open the detail panel.
 */
enum class RowsTab(val origin: String) { CHECKPOINTS("checkpoints"), SESSIONS("sessions"), TRAILS("trails") }

/**
 * One tab of the Entire tool window: a tree of rows, or a line that says why
 * there are none to show.
 *
 * A row is plain text and an ID. The tree is rebuilt from each new set of
 * rows, and what was open and what was selected is put back by ID.
 */
class RowsPanel(private val project: Project, parent: Disposable, private val tab: RowsTab) : SimpleToolWindowPanel(true, true), FollowedView {
    private val service = EntireService.getInstance(project)
    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = object : Tree(model) {
        override fun getToolTipText(event: MouseEvent): String? = getPathForLocation(event.x, event.y)?.let(::rowAt)?.let(::hoverText)
    }

    /** The Load More rows a load on scroll was started for, by row and by how many rows the list showed then. */
    private val autoLoaded = HashSet<String>()
    private val notice = JBLabel()
    private val noticeLink = ActionLink(EntireBundle.message("link.logIn")) { service.openSetup(StepId.LOGIN) }

    /** The field the tab is searched with. Typing filters the rows. Enter also searches Entire, in the Checkpoints and Sessions tabs. */
    val searchField = SearchTextField(false)

    /** The words the rows are filtered by. Empty for none. */
    private var filterText = ""

    /** What the tab was last given to show, before the filter. */
    private var lastView: RowsView? = null

    /** The notice above the rows, with its link. */
    private var noticeRow: JPanel? = null

    /** A row to select that the tree did not have yet when it was asked for. It is selected when the rows that hold it arrive. */
    private var pendingSelection: List<String>? = null

    /** When to stop waiting for that row. Rows that arrive much later must not move a selection the reader has made since. */
    private var pendingUntil = 0L

    /** Open rows whose children were asked for. A row is asked once for each time its children go missing. */
    private val asked = HashSet<String>()

    /** Rows that have been on screen. A row that starts out open does so the first time it appears, and not again after the user closes it. */
    private val seen = HashSet<String>()

    /** True while the tree is being rebuilt, when rows open because they were open before and not because the user opened them. */
    private var rebuilding = false

    init {
        tree.isRootVisible = false
        tree.showsRootHandles = true
        ToolTipManager.sharedInstance().registerComponent(tree)
        tree.cellRenderer = RowRenderer()
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (!SwingUtilities.isLeftMouseButton(event)) return
                    val row = tree.getPathForLocation(event.x, event.y)?.let(::rowAt) ?: return
                    // One click shows the row in the tab that each click reuses, and leaves the keys with the list.
                    // A second click moves into the tab.
                    activate(row, focus = event.clickCount > 1)
                }
            },
        )
        // The menu of the row under the pointer, or of the selected row when the keyboard asks.
        tree.addMouseListener(
            object : PopupHandler() {
                override fun invokePopup(component: Component, x: Int, y: Int) {
                    val under = tree.getPathForLocation(x, y)
                    if (under != null) tree.selectionPath = under
                    val row = (under ?: tree.selectionPath)?.let(::rowAt) ?: return
                    val menu = rowMenu(row)
                    if (menu.childrenCount == 0) return
                    ActionManager.getInstance().createActionPopupMenu("Entire.RowMenu", menu).component.show(component, x, y)
                }
            },
        )
        tree.registerKeyboardAction(
            { tree.selectionPath?.let(::rowAt)?.let { activate(it, focus = true) } },
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
            JComponent.WHEN_FOCUSED,
        )
        tree.addTreeExpansionListener(
            object : TreeExpansionListener {
                override fun treeExpanded(event: TreeExpansionEvent) {
                    if (rebuilding) return
                    val row = rowAt(event.path) ?: return
                    // Some children are read when their row is opened, and not before. Opening a row again repeats a read that failed.
                    if (row.onOpen == OnOpen.NOTHING) return
                    asked.add(row.id)
                    readChildren(row)
                }

                override fun treeCollapsed(event: TreeExpansionEvent) {}
            },
        )

        // The buttons that serve every tab are in the tool window's title bar. Only the Trails tab has one of its own,
        // which shares its row with the search field.
        val top = JPanel(BorderLayout())
        if (tab == RowsTab.TRAILS) {
            val toolbar = ActionManager.getInstance().createActionToolbar("Entire.${tab.origin}", DefaultActionGroup(FilterAction()), true)
            toolbar.targetComponent = this
            top.add(toolbar.component, BorderLayout.WEST)
        }
        searchField.textEditor.emptyText.text = EntireBundle.message(
            when (tab) {
                RowsTab.CHECKPOINTS -> "search.placeholder.checkpoints"
                RowsTab.SESSIONS -> "search.placeholder.sessions"
                RowsTab.TRAILS -> "search.placeholder.trails"
            },
        )
        searchField.border = JBUI.Borders.empty(2, 4)
        searchField.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) = filterChanged()
            },
        )
        // Enter searches Entire too. Typing never does: each search goes to Entire.
        searchField.textEditor.addActionListener { searchEntire() }
        top.add(searchField, BorderLayout.CENTER)
        setToolbar(top)

        notice.border = JBUI.Borders.empty(4, 8, 4, 4)
        noticeLink.border = JBUI.Borders.empty(4, 0, 4, 8)
        val noticeRow = JPanel(BorderLayout()).also {
            it.add(notice, BorderLayout.CENTER)
            it.add(noticeLink, BorderLayout.EAST)
            it.isVisible = false
        }
        this.noticeRow = noticeRow
        val content = JPanel(BorderLayout())
        content.add(noticeRow, BorderLayout.NORTH)
        val scroll = ScrollPaneFactory.createScrollPane(tree, true)
        // A Load More row that scrolls into view loads its list's next page.
        scroll.viewport.addChangeListener { loadMoreInView() }
        content.add(scroll, BorderLayout.CENTER)
        setContent(content)

        if (TrustedProjects.isProjectTrusted(project)) {
            // A tab on a trail is followed by no list: a trail's row is the trail, and the tab does not move off it.
            if (tab != RowsTab.TRAILS) service.follow(parent, tab.origin, this)
            val rows = when (tab) {
                RowsTab.CHECKPOINTS -> service.checkpoints
                RowsTab.SESSIONS -> service.sessionRows
                RowsTab.TRAILS -> service.trailRows
            }
            service.observe(parent, rows, ::show)
            // Nothing is read for a tab until the tab is on screen.
            UiNotifyConnector.doWhenFirstShown(this, { firstShown() }, parent)
        } else {
            // Entire runs git and its CLI in the project, which an untrusted project must not cause.
            tree.emptyText.clear()
            say(EntireBundle.message("state.untrusted.1"), EntireBundle.message("state.untrusted.2"))
        }
    }

    /** The filter changed: the rows are shown again under it, and results from Entire for other words are dropped. */
    private fun filterChanged() {
        val text = searchField.text.trim()
        if (text == filterText) return
        filterText = text
        val searched = service.chrome.value.searched
        if (searched != null && searched != text) service.clearSearch()
        lastView?.let { show(it) }
    }

    /** Searches Entire for the words in the field, in the tabs that list what Entire's search finds. */
    private fun searchEntire() {
        if (tab == RowsTab.TRAILS || filterText.isEmpty()) return
        service.searchEntire(filterText)
    }

    private fun firstShown() {
        service.ensureLoaded()
        if (tab == RowsTab.SESSIONS) service.showSessions()
        if (tab == RowsTab.TRAILS) service.showTrails()
    }

    /** The button that says which trails the tab shows, and changes it. */
    private inner class FilterAction : DumbAwareAction(EntireBundle.message("trails.filter.title"), EntireBundle.message("trails.filter.description"), AllIcons.General.Filter) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(event: AnActionEvent) {
            event.presentation.text = EntireBundle.message("trails.filter", service.chrome.value.trailFilter)
            event.presentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }

        override fun actionPerformed(event: AnActionEvent) {
            val anchor = event.inputEvent?.component ?: this@RowsPanel
            service.trailFilterChoices { choices, current -> chooseFilter(anchor, choices, current) }
        }
    }

    /**
     * Asks which trails to show. A trail is open or closed, and an open trail
     * is in a phase. The statuses come first, and then each phase by name.
     */
    private fun chooseFilter(anchor: Component, choices: List<TrailFilter>, current: TrailFilter) {
        fun describe(filter: TrailFilter): String = when {
            !filter.phase.isNullOrEmpty() -> EntireBundle.message("trails.filter.phase", words(filter.phase.orEmpty()))
            filter.status == TrailListStatus.ALL -> EntireBundle.message("trails.filter.all")
            filter.status == TrailListStatus.CLOSED -> EntireBundle.message("trails.filter.closed")
            else -> EntireBundle.message("trails.filter.open")
        }
        val group = DefaultActionGroup()
        var separated = false
        for (choice in choices) {
            if (!choice.phase.isNullOrEmpty() && !separated) {
                group.add(Separator.create(EntireBundle.message("trails.filter.phases")))
                separated = true
            }
            val chosen = trailFilterKey(choice) == trailFilterKey(current)
            val description = if (chosen) EntireBundle.message("trails.filter.shownNow", describe(choice)) else describe(choice)
            group.add(
                object : ToggleAction(trailFilterLabel(choice), description, null), DumbAware {
                    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

                    override fun isSelected(event: AnActionEvent): Boolean = chosen

                    override fun setSelected(event: AnActionEvent, state: Boolean) = service.setTrailFilter(choice)
                },
            )
        }
        JBPopupFactory.getInstance()
            .createActionGroupPopup(EntireBundle.message("trails.filter.title"), group, DataManager.getInstance().getDataContext(anchor), null, true)
            .showUnderneathOf(anchor)
    }

    private fun rowAt(path: TreePath): RowNode? = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? RowNode

    /**
     * Loads the next page of each list whose Load More row is in view. Each
     * row starts one load: the row that follows it says another count, and is
     * another row. A list whose last such load found nothing new waits for a
     * click, and so does every list while the setting is off.
     */
    private fun loadMoreInView() {
        // Under a filter, a list in view would load page after page while few rows match, to the end of the history.
        if (!tree.isShowing || !EntireSettings.getInstance().loadOnScroll || filterText.isNotEmpty()) return
        val view = tree.visibleRect
        if (view.height <= 0) return
        val first = tree.getClosestRowForLocation(view.x, view.y)
        val last = tree.getClosestRowForLocation(view.x, view.y + view.height - 1)
        if (first < 0 || last < 0) return
        val inView = HashSet<String>()
        for (index in first..last) {
            val row = tree.getPathForRow(index)?.let(::rowAt) ?: continue
            val list = row.list
            if (row.kind != RowKind.LOAD_MORE || list == null || !row.auto) continue
            val key = row.id + "|" + row.description
            inView.add(key)
            if (autoLoaded.add(key)) service.showMore(row.repoRoot, list, automatic = true)
        }
        // A row that left the view may come back as the same row, and is then loaded for again.
        autoLoaded.retainAll(inView)
    }

    /** What hovering over a row says, as the tooltip draws it. The row's text is data, so it is escaped. */
    private fun hoverText(row: RowNode): String? {
        val hover = row.hover
        val lines = when {
            row.kind == RowKind.LOAD_MORE ->
                listOf(EntireBundle.message(if (row.auto && EntireSettings.getInstance().loadOnScroll) "row.loadMore.auto" else "row.loadMore.click"))
            hover == null -> return null
            else -> hover.lines
        }
        val title = hover?.title
        val html = StringBuilder("<html>")
        // A long line wraps at a fixed width. Short ones keep the tooltip small.
        val wide = (lines + listOfNotNull(title)).any { it.length > 70 }
        if (wide) html.append("<div style='width:").append(JBUI.scale(340)).append("px'>")
        if (title != null) html.append("<b>").append(StringUtil.escapeXmlEntities(title)).append("</b>")
        for (line in lines) {
            if (html.length > 6 && !html.endsWith("px'>")) html.append("<br>")
            html.append(StringUtil.escapeXmlEntities(line))
        }
        if (wide) html.append("</div>")
        return html.append("</html>").toString()
    }

    /** What a row offers besides opening it. A row gets only the items it has something for. */
    fun rowMenu(row: RowNode): DefaultActionGroup {
        fun item(key: String, icon: Icon?, action: () -> Unit) =
            object : DumbAwareAction(EntireBundle.message(key), null, icon) {
                override fun actionPerformed(event: AnActionEvent) = action()
            }
        val menu = DefaultActionGroup()
        if (row.openable) menu.add(item("row.openInNewTab", AllIcons.Actions.OpenNewTab) { open(row, newTab = true, focus = true) })
        // A commit that Entire's search found may not be in the clone, which a diff would need.
        if (row.commitSha != null && row.kind != RowKind.SEARCH_HIT) {
            menu.add(item("row.showCommitDiff", AllIcons.Actions.Diff) { service.rowAction { showCommitDiff(row) } })
        }
        if (row.menu.web) menu.add(item("row.openOnWeb", AllIcons.General.Web) { service.rowAction { openOnWeb(row) } })
        if (row.hasTranscript) menu.add(item("row.openRawTranscript", null) { service.rowAction { openRawTranscript(row) } })
        val copies = ArrayList<DumbAwareAction>()
        if (row.checkpointId != null) copies.add(item("row.copyCheckpointId", null) { service.rowAction { copyCheckpointId(row) } })
        if (row.commitSha != null) copies.add(item("row.copyCommitSha", null) { service.rowAction { copyCommitSha(row) } })
        if (row.menu.sessionId != null) copies.add(item("row.copySessionId", null) { service.rowAction { copySessionId(row) } })
        if (row.kind == RowKind.TRAIL) copies.add(item("row.copyTrailLink", null) { service.rowAction { copyTrailLink(row) } })
        if (copies.isNotEmpty() && menu.childrenCount > 0) menu.addSeparator()
        copies.forEach { menu.add(it) }
        return menu
    }

    /** Opens what a row stands for: a trail in the trail panel, anything else in the detail panel. */
    private fun open(row: RowNode, newTab: Boolean, focus: Boolean) {
        if (row.kind == RowKind.SEARCH_HIT) {
            service.openSearchHit(row, newTab = newTab, focus = focus, origin = tab.origin)
            return
        }
        val trail = row.trail
        if (row.kind == RowKind.TRAIL && trail != null) {
            service.openTrail(TrailTarget(row.repoRoot, trail.number, trail.id), newTab = newTab, focus = focus)
            return
        }
        row.detailTarget()?.let { service.openDetail(it, newTab = newTab, focus = focus, origin = tab.origin) }
    }

    /** Asks for the children of a row that are read only when it is opened. */
    private fun readChildren(row: RowNode) {
        val sessionId = row.sessionId
        val checkpointId = row.checkpointId
        when {
            // A session's checkpoints on other branches come from Entire.
            row.kind == RowKind.SESSION && sessionId != null -> service.sessionExpanded(row.repoRoot, sessionId)
            // The sessions of a checkpoint that holds several come from the CLI.
            (row.kind == RowKind.COMMIT || row.kind == RowKind.CHECKPOINT) && checkpointId != null -> service.checkpointOpened(row.repoRoot, checkpointId)
        }
    }

    /**
     * Asks for the children of each open row that has not read them. A row
     * can be open without the reader opening it now: it was open when a
     * refresh dropped what it held, or it opened to show a selected row.
     */
    private fun readChildrenOfOpenRows() {
        val unread = HashSet<String>()
        TreeUtil.treeNodeTraverser(root).forEach { node ->
            val row = (node as? DefaultMutableTreeNode)?.userObject as? RowNode ?: return@forEach
            if (row.onOpen != OnOpen.READ) return@forEach
            unread.add(row.id)
            if (tree.isExpanded(TreePath(node.path)) && asked.add(row.id)) readChildren(row)
        }
        // A row whose children arrived, or whose read failed, is asked again the next time they are missing.
        asked.retainAll(unread)
    }

    private fun activate(row: RowNode, focus: Boolean) {
        val list = row.list
        if (row.kind == RowKind.LOAD_MORE && list != null) {
            service.showMore(row.repoRoot, list)
            return
        }
        when {
            row.kind == RowKind.SEARCH_ACTION -> searchEntire()
            row.link == EmptyLink.LOG_IN -> service.openSetup(StepId.LOGIN)
            else -> open(row, newTab = false, focus = focus)
        }
    }

    /** The rows to draw: what the tab was given, under the filter, with what the filter adds. */
    private fun filtered(view: RowsView): Pair<List<RowNode>, Set<String>> {
        if (filterText.isEmpty() || view.rows.isEmpty()) return view.rows to emptySet()
        val result = filterRows(view.rows, filterText)
        val rows = ArrayList<RowNode>()
        val root = view.rows.first().repoRoot
        val noun = when (tab) {
            RowsTab.CHECKPOINTS -> "checkpoints"
            RowsTab.SESSIONS -> "sessions"
            RowsTab.TRAILS -> "trails"
        }
        if (result.matches == 0 && result.opened.isEmpty()) {
            rows.add(RowNode(id = "$root|filter:none", kind = RowKind.MESSAGE, label = EntireBundle.message("search.noMatch", noun, filterText), description = "", repoRoot = root))
        }
        rows.addAll(result.rows)
        // Entire's search is offered until it has run for these words.
        if (tab != RowsTab.TRAILS && service.chrome.value.searched != filterText) {
            rows.add(
                RowNode(
                    id = "$root|filter:search",
                    kind = RowKind.SEARCH_ACTION,
                    label = EntireBundle.message("search.entire", filterText),
                    description = EntireBundle.message("search.entire.description"),
                    repoRoot = root,
                    hover = Hover(null, listOf(EntireBundle.message("search.entire.hover"))),
                ),
            )
        }
        return rows to result.opened
    }

    private fun show(view: RowsView) {
        lastView = view
        val (rows, opened) = filtered(view)
        val expanded = HashSet<String>()
        TreeUtil.collectExpandedPaths(tree).forEach { path -> rowAt(path)?.let { expanded.add(it.id) } }
        val selected = tree.selectionPath?.let(::rowAt)?.id

        rebuilding = true
        try {
            root.removeAllChildren()
            rows.forEach { root.add(treeNode(it)) }
            model.reload()

            var selection: TreePath? = null
            TreeUtil.treeNodeTraverser(root).forEach { node ->
                val row = (node as? DefaultMutableTreeNode)?.userObject as? RowNode ?: return@forEach
                val path = TreePath(node.path)
                val firstTime = seen.add(row.id)
                val startsOpen = row.kind == RowKind.REPO || row.kind == RowKind.SESSION_GROUP || row.kind == RowKind.SEARCH_GROUP
                // A row kept only because a row under it matches the filter opens, so the match is in view.
                if (row.id in expanded || row.id in opened || (firstTime && startsOpen)) tree.expandPath(path)
                if (row.id == selected) selection = path
            }
            selection?.let { tree.selectionPath = it }
            applyPendingSelection()
        } finally {
            rebuilding = false
        }
        readChildrenOfOpenRows()
        // After the tree has laid its new rows out, and not in the middle of it.
        SwingUtilities.invokeLater { loadMoreInView() }

        notice.text = view.notice.orEmpty()
        noticeLink.isVisible = view.noticeLink == EmptyLink.LOG_IN
        noticeRow?.isVisible = view.notice != null
        showEmptyText(view)
        // With rows on screen, the tree is described by its rows.
        if (rows.isNotEmpty()) tree.accessibleContext.accessibleDescription = null
    }

    override suspend fun visible(): Boolean = withContext(Dispatchers.EDT) { tree.isShowing }

    override suspend fun selected(): RowNode? = withContext(Dispatchers.EDT) { tree.selectionPath?.let(::rowAt) }

    override suspend fun select(path: List<String>) = withContext(Dispatchers.EDT) {
        pendingSelection = path
        pendingUntil = System.nanoTime() + PENDING_SELECTION_NANOS
        applyPendingSelection()
        readChildrenOfOpenRows()
    }

    /**
     * Selects the row a detail tab moved to. The keyboard focus stays where
     * it is: in the tab, on the button that was pressed. Selecting a row
     * opens nothing, because only a click or the Enter key opens a tab.
     */
    private fun applyPendingSelection() {
        val wanted = pendingSelection ?: return
        if (System.nanoTime() > pendingUntil) {
            pendingSelection = null
            return
        }
        var node = root
        for (id in wanted) {
            node = node.children().asSequence().filterIsInstance<DefaultMutableTreeNode>().firstOrNull { (it.userObject as? RowNode)?.id == id }
                ?: return
        }
        pendingSelection = null
        val path = TreePath(node.path)
        val wasRebuilding = rebuilding
        // Opening the rows above the one to select is not the reader opening them.
        rebuilding = true
        try {
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        } finally {
            rebuilding = wasRebuilding
        }
    }

    private fun treeNode(row: RowNode): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(row)
        row.children.forEach { node.add(treeNode(it)) }
        return node
    }

    /** What the tab says when it has no rows. Each state says what is missing and offers the next step. */
    private fun showEmptyText(view: RowsView) {
        tree.emptyText.clear()
        val state = view.state
        val retry = { service.refresh() }
        // A tab whose reasons are its own says them in its own words.
        val own = view.empty
        if (own != null) {
            say(*own.lines.toTypedArray())
            for (offered in own.links) {
                when (offered) {
                    EmptyLink.RETRY -> link(EntireBundle.message("link.retry"), retry)
                    EmptyLink.COPY_LOGIN_COMMAND -> link(EntireBundle.message("link.copyCommand")) { service.copy("entire login", "Copied entire login") }
                    EmptyLink.SHOW_LOG -> link(EntireBundle.message("link.showLog")) { service.showLog() }
                    EmptyLink.CHANGE_FILTER -> link(EntireBundle.message("link.changeFilter")) {
                        service.trailFilterChoices { choices, current -> chooseFilter(tree, choices, current) }
                    }
                    EmptyLink.LOG_IN -> link(EntireBundle.message("link.logIn")) { service.openSetup(StepId.LOGIN) }
                    EmptyLink.SET_UP -> link(EntireBundle.message("link.setUp")) { service.openSetup(null) }
                }
            }
            return
        }
        if (tab == RowsTab.TRAILS) {
            // The tab exists only for a repository that is ready, so all it can be without rows of its own is loading.
            say(EntireBundle.message("state.loading"))
            return
        }
        val sessions = tab == RowsTab.SESSIONS
        // The Checkpoints tab explains each state. The Sessions tab, which is read second, says it in one line.
        fun lines(key: String): Array<String> =
            if (sessions) arrayOf(EntireBundle.message("$key.1")) else arrayOf(EntireBundle.message("$key.1"), EntireBundle.message("$key.2"))
        when (state) {
            ViewState.LOADING -> say(EntireBundle.message("state.loading"))
            ViewState.NO_FOLDER -> say(EntireBundle.message(if (sessions) "state.noFolder.sessions" else "state.noFolder"))
            ViewState.NOT_GIT -> say(*lines("state.notGit"))
            // At most two actions each, and the help topic for the state. The title bar's Refresh looks again after a change made elsewhere.
            ViewState.CLI_MISSING -> {
                say(*lines("state.cliMissing"))
                link(EntireBundle.message("link.setUp")) { service.openSetup(StepId.CLI) }
                // The CLI may be installed where the search does not look. The settings page takes its path.
                if (!sessions) link(EntireBundle.message("link.setCliPath")) { service.openSettings() }
                help(EntireBundle.message("help.install"), INSTALL_HELP)
            }
            ViewState.CLI_TOO_OLD -> {
                say(EntireBundle.message("state.cliTooOld"))
                link(EntireBundle.message("link.setUp")) { service.openSetup(StepId.CLI) }
                help(EntireBundle.message("help.install"), INSTALL_HELP)
            }
            ViewState.NOT_ENABLED -> {
                say(*lines("state.notEnabled"))
                link(EntireBundle.message("link.setUp")) { service.openSetup(StepId.ENABLE) }
                help(EntireBundle.message("help.quickstart"), QUICKSTART_HELP)
            }
            ViewState.READY ->
                if (sessions) {
                    say(EntireBundle.message("state.empty.sessions"))
                } else {
                    say(EntireBundle.message("state.empty.1"), EntireBundle.message("state.empty.2"))
                    help(EntireBundle.message("help.quickstart"), QUICKSTART_HELP)
                }
        }
    }

    /** A link to a page of Entire's documentation, after the question-mark icon that marks help. */
    private fun help(label: String, url: String) {
        tree.emptyText.appendLine(AllIcons.General.ContextHelp, label, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { service.browse(url) }
    }

    /** Writes the lines where the rows would be, and gives them to a screen reader as what the empty tree is. */
    private fun say(vararg lines: String) {
        lines.forEach { tree.emptyText.appendLine(it) }
        tree.accessibleContext.accessibleDescription = lines.joinToString(" ")
    }

    private fun link(label: String, action: () -> Unit) {
        tree.emptyText.appendLine(label, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { action() }
    }

    /** A small circle for a session that is running: filled when the agent is working, an outline when it waits. */
    private class DotIcon(private val filled: Boolean) : Icon {
        override fun getIconWidth(): Int = JBUI.scale(16)

        override fun getIconHeight(): Int = JBUI.scale(16)

        override fun paintIcon(component: Component?, graphics: Graphics, x: Int, y: Int) {
            val g = graphics.create() as Graphics2D
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val size = JBUI.scale(8)
                val offset = (iconWidth - size) / 2
                if (filled) {
                    g.color = GREEN
                    g.fillOval(x + offset, y + offset, size, size)
                } else {
                    g.color = UIUtil.getContextHelpForeground()
                    g.drawOval(x + offset, y + offset, size - 1, size - 1)
                }
            } finally {
                g.dispose()
            }
        }
    }

    private class RowRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean,
        ) {
            val node = (value as? DefaultMutableTreeNode)?.userObject as? RowNode ?: return
            val drawn: Icon? = when (node.kind) {
                RowKind.REPO -> AllIcons.Nodes.Folder
                RowKind.COMMIT -> AllIcons.Vcs.CommitNode
                RowKind.CHECKPOINT -> AllIcons.Nodes.Bookmark
                RowKind.OTHER_GROUP -> AllIcons.Nodes.Folder
                RowKind.LOAD_MORE -> AllIcons.General.ArrowDown
                RowKind.SESSION -> when (node.status) {
                    "active" -> ACTIVE
                    "idle" -> IDLE
                    else -> AllIcons.General.Balloon
                }
                RowKind.SLOT -> AllIcons.General.Balloon
                RowKind.TRAIL -> trailIcon(node.trail)
                RowKind.SEARCH_GROUP -> AllIcons.Actions.Find
                RowKind.SEARCH_ACTION -> AllIcons.Actions.Search
                RowKind.SEARCH_HIT -> when (node.search?.type) {
                    "session" -> AllIcons.General.Balloon
                    "commit" -> AllIcons.Vcs.CommitNode
                    else -> AllIcons.Nodes.Bookmark
                }
                // These two only say something. They have no icon, so they do not read as something to open.
                RowKind.SESSION_GROUP, RowKind.MESSAGE -> null
            }
            // A checkpoint with no session data is drawn dimmed, so it reads as lesser before it is opened. So is a closed trail.
            val dimmed = node.muted || node.trail?.icon == TrailIcon.CLOSED
            icon = if (dimmed && drawn != null) IconLoader.getDisabledIcon(drawn) else drawn
            val quiet = node.muted || node.kind == RowKind.MESSAGE
            val style = when {
                node.link != null || node.kind == RowKind.SEARCH_ACTION -> SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES
                quiet -> SimpleTextAttributes.GRAYED_ATTRIBUTES
                else -> SimpleTextAttributes.REGULAR_ATTRIBUTES
            }
            append(node.label, style)
            if (node.description.isNotEmpty()) append("  " + node.description, SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private companion object {
        /** The icon of a trail row, which follows the trail's phase. A trail whose branch is checked out here is drawn in green. */
        fun trailIcon(trail: TrailRow?): Icon {
            val icon = when (trail?.icon) {
                TrailIcon.COMPLETE -> AllIcons.General.InspectionsOK
                TrailIcon.PLANNING -> AllIcons.Vcs.BranchNode
                else -> AllIcons.Vcs.Branch
            }
            return if (trail?.checkedOut == true && trail.icon != TrailIcon.COMPLETE) IconUtil.colorize(icon, GREEN) else icon
        }


        /** How long a row to select is waited for. The rows that hold it are already on their way when it is asked for. */
        const val PENDING_SELECTION_NANOS = 5_000_000_000L
        val GREEN = JBColor(Color(0x36, 0x87, 0x46), Color(0x57, 0x96, 0x5c))
        val ACTIVE: Icon = DotIcon(filled = true)
        val IDLE: Icon = DotIcon(filled = false)
    }
}

/** The pages of Entire's documentation that the empty states link to. */
private const val INSTALL_HELP = "https://docs.entire.com/installation"
private const val QUICKSTART_HELP = "https://docs.entire.com/quickstart"
