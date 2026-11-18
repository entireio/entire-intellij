package io.entire.spikes

import com.intellij.configurationStore.saveSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComponentValidator
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.components.JBTextField
import com.intellij.ui.scale.JBUIScale
import com.intellij.ui.tabs.impl.TabLabel
import com.intellij.ui.icons.toStrokeIcon
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.UIUtil
import io.entire.app.brain.BrainViewState
import io.entire.app.rows.RowKind
import io.entire.app.rows.RowNode
import io.entire.app.repo.RepoManager
import io.entire.core.json.parseJson
import io.entire.core.protocol.DetailMode
import io.entire.core.trails.TrailFilter
import io.entire.core.trails.TrailListStatus
import io.entire.core.trails.trailFilterLabel
import io.entire.intellij.EntireService
import io.entire.intellij.editor.EntirePanelFile
import io.entire.intellij.editor.PanelFiles
import io.entire.intellij.editor.PanelHost
import io.entire.intellij.editor.TrailHost
import io.entire.intellij.settings.EntireSettings
import io.entire.intellij.toolwindow.EntireTabs
import io.entire.intellij.toolwindow.RowsPanel
import io.entire.intellij.toolwindow.SetupPanel
import com.intellij.ui.components.JBCheckBox
import git4idea.config.GitExecutableManager
import java.awt.Color
import java.awt.Dialog
import java.awt.Window
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.RootPaneContainer
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.UIManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Opens the Entire tool window and writes down what its Checkpoints tab
 * shows, the way a person would read it: the rows as drawn, or the line that
 * says why there are none. Started with `-Dentire.spike.inspect=<file>`.
 */
object PluginInspection {
    suspend fun run(project: Project, report: Report, settingsPicture: Path) = coroutineScope {
        val watch = launch { watchToolWindows(project, report) }
        try {
            inspect(project, report, settingsPicture)
        } finally {
            watch.cancel()
        }
    }

    /** Writes a line each time a tool window opens or closes, so that a tree that left the screen can be explained. */
    private suspend fun watchToolWindows(project: Project, report: Report) {
        val started = System.nanoTime()
        var last = ""
        while (true) {
            val now = withContext(Dispatchers.EDT) {
                val manager = ToolWindowManager.getInstance(project)
                val entire = manager.getToolWindow("Entire")
                val open = manager.toolWindowIds.filter { manager.getToolWindow(it)?.isVisible == true }.sorted()
                "Entire visible ${entire?.isVisible}, type ${entire?.type}, auto-hide ${entire?.isAutoHide}, active ${manager.activeToolWindowId}, open $open"
            }
            if (now != last) {
                report.info("inspect.toolWindows", "at ${elapsedMs(started)} ms: $now")
                last = now
            }
            delay(150)
        }
    }

    private suspend fun inspect(project: Project, report: Report, settingsPicture: Path) {
        val toolWindow = withContext(Dispatchers.EDT) { ToolWindowManager.getInstance(project).getToolWindow("Entire") }
        if (toolWindow == null) {
            report.fail("inspect.toolWindow", "the Entire tool window is not registered")
            return
        }
        // The tool window has a button only in a project with a Git repository.
        val available = withContext(Dispatchers.EDT) { toolWindow.isAvailable }
        val git = generateSequence(project.basePath?.let { Path.of(it) }) { it.parent }.any { Files.exists(it.resolve(".git")) }
        if (!git) {
            report.check("inspect.toolWindow.noGit", !available, "a project without a Git repository has the Entire tool window available: $available")
            return
        }
        report.check("inspect.toolWindow.available", available, "a project in a Git repository has the Entire tool window available: $available")
        val shown = CompletableDeferred<Unit>()
        withContext(Dispatchers.EDT) { toolWindow.activate { shown.complete(Unit) } }
        withTimeoutOrNull(10_000) { shown.await() }

        val tree = withTimeoutOrNull(15_000) {
            var found: Tree? = null
            while (found == null) {
                found = withContext(Dispatchers.EDT) {
                    tabsOf(toolWindow)?.panel("Checkpoints")?.let { UIUtil.findComponentOfType(it, Tree::class.java) }
                }
                if (found == null) delay(200)
            }
            found
        }
        if (tree == null) {
            report.fail("inspect.tree", "the tool window has no tree")
            return
        }
        report.info("inspect.tabs", withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.names().orEmpty().joinToString() })
        // A project that needs setting up opens on the Setup tab, where the Checkpoints tree is not on screen.
        when (System.getProperty("entire.spike.only")) {
            "setup" -> {
                withTimeoutOrNull(60_000) { while (EntireService.getInstance(project).chrome.value.let { !it.setupNeeded && !it.ready }) delay(200) }
                setupStates(project, toolWindow, report)
                framePicture(project, settingsPicture.resolveSibling("setup.png"))
                return
            }
            "setup-run" -> {
                withTimeoutOrNull(60_000) { while (!EntireService.getInstance(project).chrome.value.setupNeeded) delay(200) }
                setupRun(project, toolWindow, report)
                framePicture(project, settingsPicture.resolveSibling("setup-done.png"))
                return
            }
        }
        // A tab reads nothing, and follows no detail tab, until it is on screen. The IDE can hide the tool
        // window again while it restores the project's layout, so it is opened until it stays open.
        val waited = System.nanoTime()
        val onScreen = withTimeoutOrNull(60_000) {
            while (!withContext(Dispatchers.EDT) { tree.isShowing }) {
                withContext(Dispatchers.EDT) { toolWindow.activate(null) }
                delay(500)
            }
            true
        }
        report.check("inspect.onScreen", onScreen == true, "the Checkpoints tree was on screen after ${elapsedMs(waited)} ms")
        selectedIcon(toolWindow, report)
        restoredTab(project, report)

        val started = System.nanoTime()
        val settled = withTimeoutOrNull(90_000) {
            while (true) {
                val (rows, empty) = withContext(Dispatchers.EDT) { tree.rowCount to emptyText(tree) }
                if (rows > 0 || (empty.isNotEmpty() && !empty.startsWith("Loading"))) break
                delay(200)
            }
            true
        }
        report.check("inspect.leftLoading", settled == true, "after ${elapsedMs(started)} ms")

        when (System.getProperty("entire.spike.only")) {
            "settings" -> {
                settingsPage(project, tree, report)
                settingsDialog(project, report, settingsPicture)
                return
            }
            "trails" -> {
                trailsTab(project, toolWindow, report, settingsPicture.resolveSibling("trails.png"))
                return
            }
            "brain" -> {
                brainTab(project, toolWindow, report)
                return
            }
            "scroll" -> {
                loadOnScroll(tree, report)
                return
            }
            "search" -> {
                search(project, toolWindow, report, settingsPicture)
                return
            }

            "refresh" -> {
                automaticRefresh(project, tree, report)
                return
            }
        }

        // The agent and the place in the session arrive a moment later, from the Entire CLI.
        delay(8_000)
        describe(tree, report, "inspect")

        rowExtras(project, toolWindow, tree, report, "inspect.row")
        automaticRefresh(project, tree, report)
        openFirstRow(project, toolWindow, tree, report)
        keepOnScreen(toolWindow, tree, report, "stepping to a row beyond the list")
        followBeyondList(project, tree, report)

        val loadMore = withContext(Dispatchers.EDT) { (0 until tree.rowCount).lastOrNull { rowText(tree, it).startsWith("Load more") } }
        if (loadMore != null) {
            val before = withContext(Dispatchers.EDT) { tree.rowCount }
            withContext(Dispatchers.EDT) {
                tree.setSelectionRow(loadMore)
                tree.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
                    ?.actionPerformed(ActionEvent(tree, ActionEvent.ACTION_PERFORMED, "enter"))
            }
            val grew = withTimeoutOrNull(30_000) {
                while (withContext(Dispatchers.EDT) { tree.rowCount } == before) delay(200)
                true
            }
            val after = withContext(Dispatchers.EDT) { tree.rowCount }
            report.check("inspect.loadMore", grew == true && after > before, "rows went from $before to $after")
            describe(tree, report, "inspect.afterLoadMore")
        }

        keepOnScreen(toolWindow, tree, report, "opening a checkpoint that holds several sessions")
        sessionsOfCheckpoint(project, tree, report)
        keepOnScreen(toolWindow, tree, report, "selecting the Sessions tab")
        sessionsTab(project, toolWindow, report)
        trailsTab(project, toolWindow, report, settingsPicture.resolveSibling("trails.png"))
        settingsPage(project, tree, report)
        settingsDialog(project, report, settingsPicture)
        // Last, because it leaves a graph server running for the closing IDE to stop.
        brainTab(project, toolWindow, report)
    }

    /** A picture of the project's IDE window, as it is drawn now. */
    private suspend fun framePicture(project: Project, picture: Path) {
        delay(1_000)
        withContext(Dispatchers.EDT) {
            val root = WindowManager.getInstance().getFrame(project)?.rootPane ?: return@withContext
            val scale = JBUIScale.sysScale(root).toDouble()
            val image = BufferedImage((root.width * scale).toInt(), (root.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.scale(scale, scale)
            root.paint(graphics)
            graphics.dispose()
            ImageIO.write(image, "png", picture.toFile())
        }
    }

    /** What a form shows, as a reader sees it: its labels, its text, and its buttons. */
    private fun formText(component: java.awt.Component): List<String> {
        val found = ArrayList<String>()
        fun visit(item: java.awt.Component) {
            when (item) {
                is JButton -> found.add("[${item.text}]")
                is JLabel -> item.text?.takeIf { it.isNotBlank() }?.let { found.add(it) }
                is JTextComponent -> item.text?.takeIf { it.isNotBlank() }?.let { found.add(it) }
            }
            if (item is java.awt.Container) item.components.forEach(::visit)
        }
        visit(component)
        return found
    }

    private fun vizProcesses(): List<String> =
        ProcessHandle.allProcesses().map { it.info().commandLine().orElse("") }.filter { it.contains("brain") && it.contains(" viz") }.toList()

    private fun answers(url: String): Int =
        try {
            val connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 2_000
            connection.readTimeout = 4_000
            connection.responseCode.also { connection.disconnect() }
        } catch (e: java.io.IOException) {
            -1
        }

    /**
     * The Brain tab: what it says about the repository's brain, and that View
     * Brain starts the graph server, hands its address to the browser, and
     * that Stop ends it. No browser opens: the check takes the address.
     */
    private suspend fun brainTab(project: Project, toolWindow: ToolWindow, report: Report) {
        val service = EntireService.getInstance(project)
        val content = withTimeoutOrNull(20_000) {
            var found = contentNamed(toolWindow, "Brain")
            while (found == null) {
                delay(300)
                found = contentNamed(toolWindow, "Brain")
            }
            found
        }
        if (content == null) {
            report.fail("inspect.brain.tab", "the tool window has no Brain tab")
            return
        }
        tabsFit(toolWindow, report)
        // The plugin's log is read afterwards, to see that the brain was not read before this line.
        Logger.getInstance("#io.entire.spikes").info("ENTIRE-SPIKE selecting the Brain tab")
        withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.select("Brain") }
        withTimeoutOrNull(30_000) {
            while (!withContext(Dispatchers.EDT) { content.isShowing }) {
                withContext(Dispatchers.EDT) { toolWindow.activate(null) }
                delay(500)
            }
        }
        val started = System.nanoTime()
        val read = withTimeoutOrNull(60_000) {
            while (service.brainSections.value.let { it.isEmpty() || it.any { section -> section.state == BrainViewState.LOADING } }) delay(200)
            service.brainSections.value
        }
        val section = read?.firstOrNull()
        report.check(
            "inspect.brain.read",
            section != null,
            "after ${elapsedMs(started)} ms: ${read?.size} repository, state ${section?.state}, ${section?.details?.size} lines, " +
                "${section?.details?.count { it.attention }} of them marked for attention",
        )
        if (section == null) return
        delay(500)
        report.info("inspect.brain.form", withContext(Dispatchers.EDT) { formText(content) }.joinToString(" | ") { it.take(90) }.take(900))
        if (section.state != BrainViewState.READY) return

        // View Brain starts the server and hands its address to the browser.
        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        val browser = service.browse
        service.browse = { opened.add(it) }
        try {
            val before = vizProcesses().size
            val clicked = System.nanoTime()
            service.viewBrain(section.root)
            val up = withTimeoutOrNull(40_000) {
                while (opened.isEmpty() || service.brainSections.value.firstOrNull()?.running == null) delay(200)
                true
            }
            val url = opened.firstOrNull().orEmpty()
            val code = if (url.isEmpty()) -1 else withContext(Dispatchers.IO) { answers(url) }
            report.check(
                "inspect.brain.view",
                up == true && Regex("http://127\\.0\\.0\\.1:\\d+").matches(url) && code == 200,
                "after ${elapsedMs(clicked)} ms the browser was handed $url, which answers with HTTP $code. The tab says the graph runs on " +
                    "${service.brainSections.value.firstOrNull()?.running}. Graph processes went from $before to ${vizProcesses().size}",
            )

            // A second click opens the page that is already served, without a second server.
            service.viewBrain(section.root)
            withTimeoutOrNull(10_000) { while (opened.size < 2) delay(100) }
            report.check(
                "inspect.brain.viewAgain",
                opened.size == 2 && opened[0] == opened[1],
                "a second View Brain handed over ${opened.getOrNull(1)}, with ${vizProcesses().size} graph processes running",
            )

            // Stop ends the server and everything it started.
            service.stopBrain(section.root)
            val down = withTimeoutOrNull(15_000) {
                while (service.brainSections.value.firstOrNull()?.running != null || vizProcesses().size > before) delay(200)
                true
            }
            val after = if (url.isEmpty()) -1 else withContext(Dispatchers.IO) { answers(url) }
            report.check(
                "inspect.brain.stop",
                down == true && after == -1,
                "after Stop the tab shows no running graph: ${service.brainSections.value.firstOrNull()?.running == null}, " +
                    "${vizProcesses().size} graph processes are left, and $url ${if (after == -1) "no longer answers" else "still answers with HTTP $after"}",
            )

            // Left running, a server is stopped by the closing IDE. The run script looks for it after the IDE has exited.
            if (System.getProperty("entire.spike.exit") == "true") {
                service.viewBrain(section.root)
                val again = withTimeoutOrNull(40_000) {
                    while (opened.size < 3 || service.brainSections.value.firstOrNull()?.running == null) delay(200)
                    true
                }
                report.info("inspect.brain.leftRunning", "a graph server was started again and left for the closing IDE to stop: ${again == true}, on ${opened.getOrNull(2)}")
            }
        } finally {
            service.browse = browser
        }
    }

    private fun rowsPanel(toolWindow: ToolWindow, name: String): RowsPanel? = tabsOf(toolWindow)?.panel(name) as? RowsPanel

    private fun treeOf(panel: JComponent): Tree? = UIUtil.findComponentOfType(panel, Tree::class.java)

    /** The rows of a tree as drawn, kind and text, top to bottom. */
    private fun rowsOf(tree: Tree): List<Pair<RowKind?, String>> = (0 until tree.rowCount).map { rowOf(tree, it)?.kind to rowText(tree, it) }

    /** Types into a tab's search field, and waits for the rows to settle. */
    private suspend fun type(panel: RowsPanel, text: String) {
        withContext(Dispatchers.EDT) { panel.searchField.text = text }
        delay(700)
    }

    /** What `entire search --json` finds for a query in the project, by type, as the reference. */
    private fun cliSearchCounts(project: Project, query: String): Map<String, Int> {
        val entire = EntireService.getInstance(project).cliPath ?: return emptyMap()
        val process = ProcessBuilder(listOf(entire, "search", "--json", "--limit", "20", "--page", "1", "--", query))
            .directory(java.io.File(project.basePath.orEmpty())).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        val results = (parseJson(text) as? kotlinx.serialization.json.JsonObject)?.get("results") as? kotlinx.serialization.json.JsonArray ?: return emptyMap()
        return results.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.get("type")?.let { t -> (t as kotlinx.serialization.json.JsonPrimitive).content } }
            .groupingBy { it }.eachCount()
    }

    /**
     * Search: the filter in the Checkpoints, Sessions, and Trails tabs, and
     * Entire's search on Enter, compared with what the CLI finds for the same
     * words. Opening a session hit and a commit hit.
     */
    private suspend fun search(project: Project, toolWindow: ToolWindow, report: Report, pictures: Path) {
        val service = EntireService.getInstance(project)
        // The log holds earlier runs too, so this run's searches are counted from here.
        Logger.getInstance("#io.entire.spikes").info("ENTIRE-SPIKE search begins")
        val checkpoints = rowsPanel(toolWindow, "Checkpoints") ?: return report.fail("inspect.search", "no Checkpoints tab")
        val tree = withContext(Dispatchers.EDT) { treeOf(checkpoints) } ?: return
        withTimeoutOrNull(60_000) { while (withContext(Dispatchers.EDT) { tree.rowCount } == 0) delay(200) }
        delay(3_000)
        val before = withContext(Dispatchers.EDT) { tree.rowCount }

        // The filter.
        type(checkpoints, "redirect")
        val filtered = withContext(Dispatchers.EDT) { rowsOf(tree) }
        val listed = filtered.filter { it.first == RowKind.COMMIT || it.first == RowKind.CHECKPOINT }
        report.check(
            "inspect.search.filter",
            listed.isNotEmpty() && listed.size < before && listed.all { it.second.contains("redirect", ignoreCase = true) } &&
                filtered.any { it.first == RowKind.SEARCH_ACTION },
            "\"redirect\" left ${listed.size} of $before rows: ${listed.joinToString(" | ") { it.second.take(60) }}. Last rows: ${filtered.takeLast(2).map { it.second.take(60) }}",
        )
        framePicture(project, pictures.resolveSibling("filter.png"))
        type(checkpoints, "")
        val restored = withContext(Dispatchers.EDT) { tree.rowCount }
        report.check("inspect.search.cleared", restored == before, "clearing the field shows $restored rows, as before ($before)")

        val marker = "ENTIRE-SPIKE search presses Enter"
        val searchesBefore = withContext(Dispatchers.IO) { logSince("ENTIRE-SPIKE search begins") }.count { it.contains("EntireService - entire search") }
        report.check("inspect.search.notWhileTyping", searchesBefore == 0, "entire search ran $searchesBefore times while the words were typed")

        // Entire's search, on Enter.
        val query = "sweep clock skew"
        type(checkpoints, query)
        Logger.getInstance("#io.entire.spikes").info(marker)
        withContext(Dispatchers.EDT) { checkpoints.searchField.textEditor.postActionEvent() }
        val found = withTimeoutOrNull(60_000) {
            while (true) {
                val rows = withContext(Dispatchers.EDT) { rowsOf(tree) }
                val group = rows.indexOfFirst { it.first == RowKind.SEARCH_GROUP }
                if (group >= 0 && rows.drop(group + 1).none { it.second.startsWith("Searching") } && rows.drop(group + 1).isNotEmpty()) return@withTimeoutOrNull rows
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        val reference = withContext(Dispatchers.IO) { cliSearchCounts(project, query) }
        val commitHits = found.orEmpty().count { it.first == RowKind.SEARCH_HIT }
        report.check(
            "inspect.search.entire",
            found != null && commitHits == (reference["commit"] ?: 0) + (reference["checkpoint"] ?: 0),
            "Enter listed $commitHits commit and checkpoint hits in the Checkpoints tab. The CLI finds $reference for the same words. " +
                "Group: ${found?.firstOrNull { it.first == RowKind.SEARCH_GROUP }?.second}",
        )

        report.info(
            "inspect.search.group",
            found.orEmpty().dropWhile { it.first != RowKind.SEARCH_GROUP }.joinToString(" | ") { "${it.first}: ${it.second.take(110)}" }.take(900),
        )

        framePicture(project, pictures.resolveSibling("search.png"))

        // The same search feeds the Sessions tab.
        val sessions = rowsPanel(toolWindow, "Sessions")
        if (sessions != null) {
            tabsOf(toolWindow)?.let { withContext(Dispatchers.EDT) { it.select("Sessions") } }
            val sessionTree = withContext(Dispatchers.EDT) { treeOf(sessions) }
            if (sessionTree != null) {
                type(sessions, query)
                val sessionRows = withTimeoutOrNull(30_000) {
                    var rows = withContext(Dispatchers.EDT) { rowsOf(sessionTree) }
                    while (rows.none { it.first == RowKind.SEARCH_HIT }) {
                        delay(300)
                        rows = withContext(Dispatchers.EDT) { rowsOf(sessionTree) }
                    }
                    rows
                }
                val sessionHits = sessionRows.orEmpty().count { it.first == RowKind.SEARCH_HIT }
                report.check(
                    "inspect.search.sessions",
                    sessionHits == (reference["session"] ?: 0),
                    "the Sessions tab lists $sessionHits session hits for the same words, with no second search: " +
                        "${withContext(Dispatchers.IO) { logSince(marker) }.count { it.contains("EntireService - entire search") }} search ran after Enter",
                )
                // A session hit opens in the detail panel.
                val hitIndex = withContext(Dispatchers.EDT) { (0 until sessionTree.rowCount).firstOrNull { rowOf(sessionTree, it)?.kind == RowKind.SEARCH_HIT } }
                if (hitIndex != null) {
                    val label = withContext(Dispatchers.EDT) { rowOf(sessionTree, hitIndex)?.label.orEmpty() }
                    pressEnter(sessionTree, hitIndex)
                    val shown = withTimeoutOrNull(60_000) {
                        var snapshot = PanelFiles.getInstance(project).previewHost()?.snapshot()
                        while (snapshot?.header == null && snapshot?.error == null) {
                            delay(300)
                            snapshot = PanelFiles.getInstance(project).previewHost()?.snapshot()
                        }
                        snapshot
                    }
                    report.check(
                        "inspect.search.openSession",
                        shown?.header != null,
                        "the hit \"${label.take(60)}\" opened as \"${shown?.header?.title}\" (${shown?.header?.mode}), checkpoint ${shown?.header?.checkpointId}, error ${shown?.error}",
                    )
                }
                type(sessions, "")
            }
            tabsOf(toolWindow)?.let { withContext(Dispatchers.EDT) { it.select("Checkpoints") } }
        }

        // A commit hit opens its checkpoint when the clone has the commit, and its page otherwise.
        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        val browser = service.browse
        service.browse = { opened.add(it) }
        try {
            val hitIndex = withContext(Dispatchers.EDT) { (0 until tree.rowCount).firstOrNull { rowOf(tree, it)?.kind == RowKind.SEARCH_HIT } }
            if (hitIndex != null) {
                val sha = withContext(Dispatchers.EDT) { rowOf(tree, hitIndex)?.commitSha.orEmpty() }
                val local = withContext(Dispatchers.IO) { git(project, "cat-file", "-t", sha) } == "commit"
                val previous = PanelFiles.getInstance(project).previewHost()?.snapshot()?.header?.checkpointId
                pressEnter(tree, hitIndex)
                val outcome = withTimeoutOrNull(30_000) {
                    while (true) {
                        val header = PanelFiles.getInstance(project).previewHost()?.snapshot()?.header
                        if (opened.isNotEmpty()) return@withTimeoutOrNull "the browser was handed ${opened.first()}"
                        if (header != null && header.checkpointId != previous && header.commit?.sha == sha) return@withTimeoutOrNull "the panel shows checkpoint ${header.checkpointId} of commit ${header.commit?.shortSha}"
                        delay(300)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
                report.check("inspect.search.openCommit", outcome != null, "commit ${sha.take(7)} is ${if (local) "in" else "not in"} the clone, and $outcome")
            }
        } finally {
            service.browse = browser
        }

        // Trails are filtered too.
        val trails = rowsPanel(toolWindow, "Trails")
        if (trails != null) {
            tabsOf(toolWindow)?.let { withContext(Dispatchers.EDT) { it.select("Trails") } }
            val trailTree = withContext(Dispatchers.EDT) { treeOf(trails) }
            if (trailTree != null) {
                withTimeoutOrNull(60_000) { while (withContext(Dispatchers.EDT) { trailTree.rowCount } == 0) delay(200) }
                val all = withContext(Dispatchers.EDT) { trailTree.rowCount }
                type(trails, "sweep")
                val left = withContext(Dispatchers.EDT) { rowsOf(trailTree) }.filter { it.first == RowKind.TRAIL }
                report.check(
                    "inspect.search.trails",
                    left.isNotEmpty() && left.size < all && left.all { it.second.contains("sweep", ignoreCase = true) },
                    "\"sweep\" left ${left.size} of $all trails: ${left.joinToString(" | ") { it.second.take(60) }}",
                )
                type(trails, "")
            }
        }
    }

    /** What the Setup tab shows, as text: each step's number, title, and state, and its status. */
    private suspend fun setupText(toolWindow: ToolWindow): List<String> =
        withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.panel("Setup")?.let { formText(it) }.orEmpty() }

    /** The state word after a step's title, such as "Done". */
    private fun stateOf(text: List<String>, title: String): String? = text.indexOfFirst { it.endsWith(title) }.takeIf { it >= 0 }?.let { text.getOrNull(it + 1) }

    /** The state of each step, once none is being checked. */
    private suspend fun settledSetup(toolWindow: ToolWindow): List<String> =
        withTimeoutOrNull(90_000) {
            var text = setupText(toolWindow)
            while (text.none { it.endsWith("Entire CLI") } || text.any { it == "Checking…" }) {
                delay(300)
                text = setupText(toolWindow)
            }
            text
        } ?: setupText(toolWindow)

    /** A step's title as the Setup tab draws it, such as "5. Brain". */
    private val STEP_TITLE = Regex("\\d+\\. (.+)")

    private val stepTitles = listOf("Entire CLI", "Log In", "Enable Entire", "Graph", "Brain", "Hosted Brain")

    private fun summary(text: List<String>): String = stepTitles.joinToString(", ") { "$it: ${stateOf(text, it)}" }

    /**
     * The Setup tab's reading of the project, with no step run: whether it
     * comes first, and what each step says.
     */
    private suspend fun setupStates(project: Project, toolWindow: ToolWindow, report: Report) {
        val service = EntireService.getInstance(project)
        // The log holds earlier runs too, some of which ran steps, so this run's commands are counted from here.
        Logger.getInstance("#io.entire.spikes").info("ENTIRE-SPIKE setup states begin")
        val needed = service.chrome.value.setupNeeded
        val first = withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.names()?.firstOrNull() }
        val selected = withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.selected() }
        if (!needed) service.openSetup(null)
        withTimeoutOrNull(10_000) { while (withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.panel("Setup") } == null) delay(200) }
        val text = settledSetup(toolWindow)
        report.check(
            "inspect.setup.shown",
            !needed || (first == "Setup" && selected == "Setup"),
            "setup needed: $needed. The tabs are ${withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.names() }}, with ${selected} selected",
        )
        report.info("inspect.setup.steps", summary(text))
        report.info("inspect.setup.text", text.filter { !it.startsWith("Changes:") }.joinToString(" | ") { it.take(110) }.take(1600))
        val commands = text.filter { it.startsWith("$ ") }
        report.info("inspect.setup.commands", commands.joinToString(" | ").ifEmpty { "none to run" })

        // The CLI step, with the path set to a file that does not exist.
        val settings = EntireSettings.getInstance()
        val before = settings.cliPath
        try {
            settings.cliPath = "/nonexistent/entire"
            service.settingsChanged()
            withTimeoutOrNull(30_000) { while (!service.chrome.value.setupNeeded) delay(200) }
            withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.select("Setup") }
            delay(1_000)
            (tabsOf(toolWindow)?.panel("Setup") as? SetupPanel)?.let { withContext(Dispatchers.EDT) { it.check() } }
            delay(2_000)
            val missing = settledSetup(toolWindow)
            report.check(
                "inspect.setup.cliMissing",
                stateOf(missing, "Entire CLI") == "To do" && missing.any { it.startsWith("$ ") && (it.contains("brew install --cask entireio/tap/entire") || it.contains("install.sh")) },
                "with the CLI path set to a missing file: ${summary(missing)}. Command: ${missing.firstOrNull { it.startsWith("$ ") }}",
            )
        } finally {
            settings.cliPath = before
            service.settingsChanged()
        }
        withTimeoutOrNull(30_000) { while (service.chrome.value.setupNeeded && needed.not()) delay(200) }
        // With the path set right again, the Setup tab reads the CLI by itself, without Check Again.
        val restored = withTimeoutOrNull(30_000) {
            var text = settledSetup(toolWindow)
            while (stateOf(text, "Entire CLI") != "Done") {
                delay(500)
                text = settledSetup(toolWindow)
            }
            text
        } ?: settledSetup(toolWindow)
        report.check("inspect.setup.cliRestored", stateOf(restored, "Entire CLI") == "Done", "with the CLI path set back: ${summary(restored)}")

        // Nothing ran: opening the Setup tab only reads.
        val ran = withContext(Dispatchers.IO) { logSince("ENTIRE-SPIKE setup states begin") }.count { it.contains("Entire setup runs:") }
        report.check("inspect.setup.nothingRan", ran == 0, "setup commands started in this run, with no click: $ran")
    }

    /** Clicks a step's button, and waits for the step to end. */
    private suspend fun runStep(toolWindow: ToolWindow, report: Report, title: String, before: (SetupPanel) -> Unit = {}) {
        val panel = tabsOf(toolWindow)?.panel("Setup") as? SetupPanel ?: return report.fail("inspect.setup.run", "no Setup tab")
        settledSetup(toolWindow)
        withContext(Dispatchers.EDT) { before(panel) }
        val started = System.nanoTime()
        // A check that is under way draws the steps without buttons until it ends, so the button is waited for.
        val clicked = withTimeoutOrNull(20_000) {
            while (true) {
                val done = withContext(Dispatchers.EDT) {
                    // The IDE can put another tool window in this one's place while the project starts.
                    if (!panel.isShowing) {
                        toolWindow.activate(null)
                        tabsOf(toolWindow)?.select("Setup")
                    }
                    val target = stepButton(panel, title)?.takeIf { it.isShowing && it.isEnabled }
                    if (formText(panel).none { it == "Checking…" } && target != null) {
                        target.doClick()
                        true
                    } else {
                        false
                    }
                }
                if (done) return@withTimeoutOrNull true
                delay(300)
            }
            @Suppress("UNREACHABLE_CODE")
            false
        } == true
        if (!clicked) {
            val seen = withContext(Dispatchers.EDT) {
                UIUtil.findComponentsOfType(panel, JButton::class.java).joinToString { "${it.text}(showing ${it.isShowing}, enabled ${it.isEnabled})" }
            }
            report.fail("inspect.setup.$title", "no enabled button for the step: ${summary(setupText(toolWindow))}. Buttons: $seen")
            return
        }
        val text = withTimeoutOrNull(300_000) {
            delay(2_000)
            var current = setupText(toolWindow)
            while (stateOf(current, title) != "Done" && withContext(Dispatchers.EDT) { UIUtil.findComponentsOfType(panel, JButton::class.java).any { it.text == "Stop" && it.isEnabled } } ||
                current.any { it == "Checking…" }
            ) {
                delay(500)
                current = setupText(toolWindow)
            }
            current
        } ?: setupText(toolWindow)
        val ran = withContext(Dispatchers.IO) { logSince("ENTIRE-SPIKE setup run begins") }.filter { it.contains("Entire setup ran:") }.map { it.substringAfter("Entire setup ran: ") }
        report.check(
            "inspect.setup.$title",
            stateOf(text, title) == "Done",
            "after ${elapsedMs(started)} ms: ${summary(text)}. Commands that ran so far: $ran",
        )
    }

    /**
     * The Run button of a step: the button whose closest ancestor that names
     * a step names this one. Buttons outside the steps, such as Check Again,
     * have no such ancestor.
     */
    private fun stepButton(panel: SetupPanel, title: String): JButton? =
        UIUtil.findComponentsOfType(panel, JButton::class.java).firstOrNull { button ->
            var at: java.awt.Container? = button.parent
            var named: String? = null
            while (at != null && at !== panel && named == null) {
                named = formText(at as JComponent).firstNotNullOfOrNull { line -> STEP_TITLE.matchEntire(line)?.groupValues?.get(1)?.takeIf { it in stepTitles } }
                at = at.parent
            }
            named == title
        }

    /**
     * Runs the steps that change only this scratch repository and scratch
     * folders: Enable, Graph, and Brain with backfill and the watcher off.
     */
    private suspend fun setupRun(project: Project, toolWindow: ToolWindow, report: Report) {
        val service = EntireService.getInstance(project)
        Logger.getInstance("#io.entire.spikes").info("ENTIRE-SPIKE setup run begins")
        withTimeoutOrNull(30_000) { while (withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.panel("Setup") } == null) delay(200) }
        val start = settledSetup(toolWindow)
        report.info("inspect.setupRun.before", summary(start))
        report.info("inspect.setupRun.commands", start.filter { it.startsWith("$ ") }.joinToString(" | "))

        runStep(toolWindow, report, "Enable Entire") { panel ->
            // A check sends no usage analytics.
            UIUtil.findComponentsOfType(panel, JBCheckBox::class.java).filter { it.text.startsWith("Share anonymous") && it.isSelected }.forEach { it.doClick() }
        }
        // The Checkpoints tab reads the repository now that Entire is enabled in it.
        val enabled = withTimeoutOrNull(60_000) {
            while (service.chrome.value.setupNeeded) delay(300)
            true
        }
        val checkpoints = rowsPanel(toolWindow, "Checkpoints")?.let { withContext(Dispatchers.EDT) { treeOf(it) } }
        report.check(
            "inspect.setupRun.checkpointsTab",
            enabled == true,
            "after Enable the project needs no setting up: ${enabled == true}. The Checkpoints tab says: ${checkpoints?.let { withContext(Dispatchers.EDT) { emptyText(it).ifEmpty { "${it.rowCount} rows" } } }}",
        )
        service.openSetup(null)
        delay(1_000)
        settledSetup(toolWindow)
        runStep(toolWindow, report, "Graph")
        settledSetup(toolWindow)
        runStep(toolWindow, report, "Brain") { panel ->
            // The watcher is for the whole machine, so it stays off here. So does distilling, which spends tokens.
            UIUtil.findComponentsOfType(panel, JBCheckBox::class.java).filter { it.text.startsWith("Keep the brain") && it.isSelected }.forEach { it.doClick() }
        }
        val end = settledSetup(toolWindow)
        report.info("inspect.setupRun.after", summary(end))
        val files = listOf(".entire/settings.json", ".entire/agent-guide.md", "AGENTS.md", "CLAUDE.md").map { it to Files.exists(Path.of(project.basePath.orEmpty(), it)) }
        report.info("inspect.setupRun.files", files.joinToString { "${it.first} ${if (it.second) "written" else "missing"}" })
    }

    private fun rowOf(tree: Tree, index: Int): RowNode? =
        (tree.getPathForRow(index)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? RowNode

    /** What hovering over a row says, as text. */
    private fun hoverOf(tree: Tree, index: Int): String {
        val bounds = tree.getRowBounds(index) ?: return ""
        val event = MouseEvent(tree, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, bounds.x + 20, bounds.y + bounds.height / 2, 0, false)
        return tree.getToolTipText(event).orEmpty().replace(Regex("<br>"), " / ").replace(Regex("<[^>]+>"), "")
    }

    /** The items of a row's menu, with a bar where the menu has a line. */
    private fun menuOf(panel: RowsPanel, row: RowNode): List<String> =
        panel.rowMenu(row).childActionsOrStubs.map { if (it is Separator) "|" else it.templatePresentation.text.orEmpty() }

    /**
     * What the first row of a tab offers besides opening: its menu, its hover
     * text, and, for a repository that Entire hosts, the page it opens on
     * entire.com. No browser opens: the check takes the address.
     */
    private suspend fun rowExtras(project: Project, toolWindow: ToolWindow, tree: Tree, report: Report, key: String, openWeb: Boolean = false) {
        val panel = withContext(Dispatchers.EDT) { SwingUtilities.getAncestorOfClass(RowsPanel::class.java, tree) as? RowsPanel } ?: return
        val row = withContext(Dispatchers.EDT) { rowOf(tree, 0) } ?: return
        val menu = withContext(Dispatchers.EDT) { menuOf(panel, row) }
        report.check("$key.menu", menu.isNotEmpty() && menu.first() == "Open in New Tab", "\"${row.label.take(50)}\" offers: ${menu.joinToString(", ")}")
        val hover = withContext(Dispatchers.EDT) { hoverOf(tree, 0) }
        report.check("$key.hover", hover.isNotBlank(), hover.take(330))
        val last = withContext(Dispatchers.EDT) { tree.rowCount - 1 }
        val lastRow = withContext(Dispatchers.EDT) { rowOf(tree, last) }
        if (lastRow?.kind == RowKind.LOAD_MORE) {
            report.info("$key.loadMore", "the Load More row offers ${withContext(Dispatchers.EDT) { menuOf(panel, lastRow) }.size} menu items, and says: ${withContext(Dispatchers.EDT) { hoverOf(tree, last) }}")
        }
        if (!openWeb || !row.menu.web) return
        val service = EntireService.getInstance(project)
        val opened = java.util.concurrent.CopyOnWriteArrayList<String>()
        val browser = service.browse
        service.browse = { opened.add(it) }
        try {
            service.rowAction { openOnWeb(row) }
            withTimeoutOrNull(30_000) { while (opened.isEmpty()) delay(200) }
            report.check("$key.openOnWeb", opened.size == 1 && opened[0].startsWith("https://"), "Open on entire.com handed the browser ${opened.firstOrNull()}")
        } finally {
            service.browse = browser
        }
    }

    private fun git(project: Project, vararg args: String): String {
        val command = listOf(GitExecutableManager.getInstance().getPathToGit(project)) + args
        val process = ProcessBuilder(command).directory(java.io.File(project.basePath.orEmpty())).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        process.waitFor()
        return output.trim()
    }

    private fun logSince(marker: String): List<String> {
        val lines = Files.readAllLines(PathManager.getLogDir().resolve("idea.log"))
        val from = lines.indexOfLast { it.contains(marker) }
        return if (from < 0) emptyList() else lines.drop(from + 1)
    }

    /**
     * Makes a commit in the project's repository, outside the IDE, and waits
     * for the Checkpoints tab to list it by itself. The commit is empty, names
     * a checkpoint the repository already has, and is taken back afterwards.
     * Then it makes the folder that holds Entire's session state, which the
     * plugin answers by reading the status again.
     */
    private suspend fun automaticRefresh(project: Project, tree: Tree, report: Report) {
        val subject = "check: automatic refresh"
        val first = withContext(Dispatchers.EDT) { rowOf(tree, 0) }
        val checkpointId = first?.checkpointId
        if (first == null || checkpointId == null) {
            report.info("inspect.refresh", "not checked: the first row is not a commit with one checkpoint")
            return
        }
        val marker = "ENTIRE-SPIKE automatic refresh begins"
        Logger.getInstance("#io.entire.spikes").info(marker)
        var committed = false
        try {
            val said = withContext(Dispatchers.IO) {
                git(
                    project, "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", "-c", "user.name=Spike", "-c", "user.email=spike@example.com",
                    "commit", "--allow-empty", "-q", "-m", subject, "-m", "Entire-Checkpoint: $checkpointId",
                )
            }
            committed = withContext(Dispatchers.IO) { git(project, "log", "-1", "--format=%s") } == subject
            val made = System.nanoTime()
            val listed = withTimeoutOrNull(20_000) {
                while (withContext(Dispatchers.EDT) { rowOf(tree, 0)?.label } != subject) delay(100)
                true
            }
            report.check(
                "inspect.refresh.commit",
                committed && listed == true,
                "a commit made outside the IDE was listed after ${elapsedMs(made)} ms, with no refresh asked for" + if (committed) "" else ". git said: $said",
            )
        } finally {
            if (committed && withContext(Dispatchers.IO) { git(project, "log", "-1", "--format=%s") } == subject) {
                withContext(Dispatchers.IO) { git(project, "reset", "-q", "--hard", "HEAD~1") }
            }
        }
        val undone = System.nanoTime()
        val back = withTimeoutOrNull(20_000) {
            while (withContext(Dispatchers.EDT) { rowOf(tree, 0)?.label } != first.label) delay(100)
            true
        }
        report.check("inspect.refresh.reset", back == true, "the commit was taken back, and the first row was \"${first.label.take(50)}\" again after ${elapsedMs(undone)} ms")

        // The session state folder appears when the first session starts. Its appearance is read as a change of session state.
        val sessions = Path.of(withContext(Dispatchers.IO) { git(project, "rev-parse", "--path-format=absolute", "--git-common-dir") }).resolve("entire-sessions")
        if (Files.exists(sessions)) {
            report.info("inspect.refresh.sessions", "not checked: $sessions exists already")
        } else {
            val before = logSince(marker).count { it.contains("entire status --json") }
            Files.createDirectory(sessions)
            try {
                val made = System.nanoTime()
                val read = withTimeoutOrNull(15_000) {
                    while (withContext(Dispatchers.IO) { logSince(marker) }.count { it.contains("entire status --json") } == before) delay(200)
                    true
                }
                report.check("inspect.refresh.sessions", read == true, "the session state folder appeared, and the status was read again after ${elapsedMs(made)} ms")
            } finally {
                Files.deleteIfExists(sessions)
            }
        }
        // Give the enrichment its wait, then list what ran. Nothing that prunes, and nothing that reads Entire over the network.
        delay(5_000)
        val ran = withContext(Dispatchers.IO) { logSince(marker) }
            .mapNotNull { Regex("EntireService - entire (\\S+(?: \\S+)?)").find(it)?.groupValues?.get(1) }
        val forbidden = ran.filter { it.startsWith("session") || it.startsWith("api") || it.startsWith("trail") || it.startsWith("brain") }
        report.check("inspect.refresh.commands", forbidden.isEmpty(), "while it refreshed by itself the plugin ran: ${ran.groupingBy { it }.eachCount()}")
    }

    /**
     * Scrolls to the Load More row. With the setting off nothing loads. With
     * it on, the next page loads without a click.
     */
    private suspend fun loadOnScroll(tree: Tree, report: Report) {
        val settings = EntireSettings.getInstance()
        val before = withContext(Dispatchers.EDT) { tree.rowCount }
        val hasMore = withContext(Dispatchers.EDT) { rowOf(tree, before - 1)?.kind == RowKind.LOAD_MORE }
        if (!hasMore) {
            report.info("inspect.scroll", "not checked: the list of $before rows has no Load More row")
            return
        }
        try {
            settings.loadOnScroll = false
            withContext(Dispatchers.EDT) { tree.scrollRowToVisible(tree.rowCount - 1) }
            delay(3_000)
            val off = withContext(Dispatchers.EDT) { tree.rowCount }
            settings.loadOnScroll = true
            val scrolled = System.nanoTime()
            withContext(Dispatchers.EDT) { tree.scrollRowToVisible(0) }
            delay(300)
            withContext(Dispatchers.EDT) { tree.scrollRowToVisible(tree.rowCount - 1) }
            val grew = withTimeoutOrNull(20_000) {
                while (withContext(Dispatchers.EDT) { tree.rowCount } == off) delay(100)
                true
            }
            val on = withContext(Dispatchers.EDT) { tree.rowCount }
            report.check(
                "inspect.scroll",
                off == before && grew == true && on > off,
                "with the setting off, scrolling to the end left $off of $before rows. With it on, the list grew to $on rows after ${elapsedMs(scrolled)} ms, with no click",
            )
        } finally {
            settings.loadOnScroll = true
        }
    }

    /** All the tabs fit on their row, with none pushed into a menu, at the width the tool window has. */
    private suspend fun tabsFit(toolWindow: ToolWindow, report: Report) {
        val fit = withContext(Dispatchers.EDT) {
            val strip = tabsOf(toolWindow) ?: return@withContext null
            val labels = UIUtil.findComponentsOfType(strip, TabLabel::class.java).filter { it.isShowing }
            Triple(labels.map { it.info.text }, labels.maxOfOrNull { it.x + it.width } ?: 0, strip.width)
        }
        val names = withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.names().orEmpty() }
        report.check(
            "inspect.tabs.fit",
            fit != null && fit.first.size == names.size && fit.second <= fit.third,
            "the tabs are $names. Their row shows ${fit?.first}, which end at ${fit?.second} of the tool window's ${fit?.third} points",
        )
    }

    /** The tabs of the Entire tool window, which are a row of its content. */
    private fun tabsOf(toolWindow: ToolWindow): EntireTabs? =
        toolWindow.contentManager.contents.firstNotNullOfOrNull { UIUtil.findComponentOfType(it.component, EntireTabs::class.java) }

    /** What a tab holds, or null while the tool window has no such tab on show. */
    private suspend fun contentNamed(toolWindow: ToolWindow, name: String): JComponent? = withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.panel(name) }

    /**
     * The Trails tab: that it exists only for a repository that Entire hosts,
     * what it lists, what opening a trail shows, what the filter does, and
     * what turning trails off does. The filter and the setting are put back.
     */
    private suspend fun trailsTab(project: Project, toolWindow: ToolWindow, report: Report, picture: Path) {
        val service = EntireService.getInstance(project)
        val content = withTimeoutOrNull(20_000) {
            var found = contentNamed(toolWindow, "Trails")
            while (found == null) {
                delay(300)
                found = contentNamed(toolWindow, "Trails")
            }
            found
        }
        if (content == null) {
            report.info("inspect.trails.tab", "the tool window has no Trails tab, as for a repository whose origin is not on Entire: tabs ${withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.names().orEmpty().joinToString() }}")
            return
        }
        tabsFit(toolWindow, report)
        // The plugin's log is read afterwards, to see that no trail was read before this line.
        Logger.getInstance("#io.entire.spikes").info("ENTIRE-SPIKE selecting the Trails tab")
        withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.select("Trails") }
        val tree = withContext(Dispatchers.EDT) { UIUtil.findComponentOfType(content, Tree::class.java) } ?: return
        withTimeoutOrNull(30_000) {
            while (!withContext(Dispatchers.EDT) { tree.isShowing }) {
                withContext(Dispatchers.EDT) { toolWindow.activate(null) }
                delay(500)
            }
        }
        val started = System.nanoTime()
        val listed = withTimeoutOrNull(60_000) {
            while (withContext(Dispatchers.EDT) { tree.rowCount } == 0) delay(200)
            true
        }
        report.check(
            "inspect.trails.listed",
            listed == true,
            "after ${elapsedMs(started)} ms the tab lists ${withContext(Dispatchers.EDT) { tree.rowCount }} rows under the filter \"${service.chrome.value.trailFilter}\"" +
                if (listed == true) "" else ", and says: ${withContext(Dispatchers.EDT) { emptyText(tree) }}",
        )
        if (listed != true) return
        describe(tree, report, "inspect.trails")
        rowExtras(project, toolWindow, tree, report, "inspect.trails.row", openWeb = true)
        // The repository's own page on entire.com, from the toolbar.
        val pages = java.util.concurrent.CopyOnWriteArrayList<String>()
        val browser = service.browse
        service.browse = { pages.add(it) }
        try {
            service.chrome.value.webRepos.firstOrNull()?.let { service.openRepoOnWeb(it.first) }
            withTimeoutOrNull(10_000) { while (pages.isEmpty()) delay(100) }
            report.check("inspect.repoOnWeb", pages.size == 1, "Open Repository on entire.com handed the browser ${pages.firstOrNull()}")
        } finally {
            service.browse = browser
        }

        // Opening the first trail draws what the row knows at once, then the trail, then its timeline.
        pressEnter(tree, 0)
        val files = PanelFiles.getInstance(project)
        val host = withTimeoutOrNull(20_000) {
            var found = files.trailPreviewHost()
            while (found == null) {
                delay(200)
                found = files.trailPreviewHost()
            }
            found
        }
        if (host == null) {
            report.fail("inspect.trails.opened", "no trail panel after Enter on the first row")
            return
        }
        val opened = System.nanoTime()
        val read = withTimeoutOrNull(90_000) {
            var snapshot = host.snapshot()
            while (snapshot.error == null && (snapshot.view == null || snapshot.view?.partial == true || snapshot.view?.timelinePending == true || snapshot.view?.refreshing == true)) {
                delay(200)
                snapshot = host.snapshot()
            }
            snapshot
        }
        val view = read?.view
        val change = view?.changes?.getOrNull(view.selected)
        val events = view?.timeline?.events.orEmpty()
        report.check(
            "inspect.trails.panel",
            view != null && read?.error == null && view.changes.isNotEmpty() == (change != null),
            "after ${elapsedMs(opened)} ms: \"${view?.title}\" #${view?.number}, ${view?.phase ?: view?.status}, ${view?.changes?.size} changes, " +
                "selected ${change?.repository}/${change?.number} on ${change?.branch}, mergeable ${change?.merge?.mergeable}, ${change?.merge?.gates?.size} gates, " +
                "checks \"${change?.merge?.checkSummary}\", ${events.size} timeline entries (more ${view?.timeline?.hasMore}), " +
                "${events.count { it.commit?.local == true }} commits in this clone, links to entire.com ${view?.canOpenOnWeb}, notice ${view?.notice ?: view?.timeline?.notice}, error ${read?.error}",
        )
        val drawn = withTimeoutOrNull(30_000) {
            var text = trailText(host)
            while (view != null && !text.contains(view.title.take(20))) {
                delay(300)
                text = trailText(host)
            }
            text
        }
        report.check("inspect.trails.pageDrawn", !drawn.isNullOrBlank(), "${drawn?.length} characters: ${drawn?.replace(Regex("\\s+"), " ")?.take(300)}")
        // A picture of the IDE with the Trails tab and the trail's page, once the page has had time to draw.
        delay(1_500)
        withContext(Dispatchers.EDT) {
            val root = WindowManager.getInstance().getFrame(project)?.rootPane ?: return@withContext
            val scale = JBUIScale.sysScale(root).toDouble()
            val image = BufferedImage((root.width * scale).toInt(), (root.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.scale(scale, scale)
            root.paint(graphics)
            graphics.dispose()
            ImageIO.write(image, "png", picture.toFile())
        }

        // A commit of the timeline that this clone has opens its checkpoint beside the trail. The first
        // trail may have no such commit, so the next rows are opened in the same tab until one has.
        var local = events.withIndex().firstOrNull { it.value.commit?.local == true && (it.value.commit?.checkpoints ?: 0) > 0 }
        var tried = 1
        val rows = withContext(Dispatchers.EDT) { tree.rowCount }
        while (local == null && tried < minOf(rows, 8)) {
            val number = host.snapshot().view?.number
            pressEnter(tree, tried)
            tried += 1
            val next = withTimeoutOrNull(60_000) {
                var snapshot = host.snapshot()
                while (snapshot.error == null &&
                    (snapshot.view == null || snapshot.view?.number == number || snapshot.view?.partial == true || snapshot.view?.timelinePending == true)
                ) {
                    delay(200)
                    snapshot = host.snapshot()
                }
                snapshot
            }
            local = next?.view?.timeline?.events.orEmpty().withIndex().firstOrNull { it.value.commit?.local == true && (it.value.commit?.checkpoints ?: 0) > 0 }
        }
        report.info("inspect.trails.retargeted", "the one trail tab showed $tried trails in turn, and now shows \"${host.snapshot().view?.title}\" #${host.snapshot().view?.number}")
        if (local != null) {
            host.receive(parseJson("""{"type":"openCheckpoint","event":${local.index},"checkpoint":0}"""))
            val checkpoint = withTimeoutOrNull(60_000) {
                var found = files.previewHost()?.snapshot()
                while (found?.header?.checkpointId == null || found.header?.commit?.shortSha != local.value.commit?.shortSha) {
                    delay(200)
                    found = files.previewHost()?.snapshot()
                }
                found
            }
            report.check(
                "inspect.trails.openCheckpoint",
                checkpoint != null && files.trailPreviewHost() === host,
                "entry ${local.index} is commit ${local.value.commit?.shortSha}. The detail panel shows checkpoint ${checkpoint?.header?.checkpointId} of commit ${checkpoint?.header?.commit?.shortSha}, and the trail tab is still open",
            )
        } else {
            report.info("inspect.trails.openCheckpoint", "no timeline entry of this trail is a commit in this clone with a checkpoint")
        }

        // With a checkpoint tab and a trail tab open together, each tab is named by what it shows, and not by its slot.
        delay(1_000)
        val titles = withContext(Dispatchers.EDT) {
            FileEditorManager.getInstance(project).openFiles.filterIsInstance<EntirePanelFile>()
                .map { it.page to EditorTabPresentationUtil.getEditorTabTitle(project, it) }
        }
        report.check(
            "inspect.trails.tabTitles",
            titles.isNotEmpty() && titles.none { it.second.contains("preview") || it.second.isBlank() || it.second == "Entire" },
            "the panel tabs read: ${titles.joinToString { "${it.first} \"${it.second}\"" }}",
        )

        // The filter: closed trails, then the open trails of one phase, then open trails again.
        suspend fun rowsUnder(filter: TrailFilter): String {
            service.setTrailFilter(filter)
            val label = trailFilterLabel(filter)
            withTimeoutOrNull(30_000) { while (service.chrome.value.trailFilter != label) delay(100) }
            // The rows of another status are read from Entire.
            delay(6_000)
            val rows = withContext(Dispatchers.EDT) { tree.rowCount }
            return "\"$label\": $rows rows" + if (rows == 0) " (${withContext(Dispatchers.EDT) { emptyText(tree) }})" else ""
        }
        try {
            val closed = rowsUnder(TrailFilter(TrailListStatus.CLOSED))
            val building = rowsUnder(TrailFilter(TrailListStatus.OPEN, "building"))
            val all = rowsUnder(TrailFilter(TrailListStatus.ALL))
            val open = rowsUnder(TrailFilter(TrailListStatus.OPEN))
            report.info("inspect.trails.filter", "$closed, $building, $all, $open")
        } finally {
            service.setTrailFilter(TrailFilter(TrailListStatus.OPEN))
        }

        // Turned off, trails are not read: the tab goes, and the open trail says why it shows nothing.
        val settings = EntireSettings.getInstance()
        try {
            settings.trailsEnabled = false
            service.trailsSettingChanged()
            val gone = withTimeoutOrNull(20_000) {
                while (contentNamed(toolWindow, "Trails") != null) delay(200)
                true
            }
            val off = withTimeoutOrNull(20_000) {
                var snapshot = host.snapshot()
                while (snapshot.error == null) {
                    delay(200)
                    snapshot = host.snapshot()
                }
                snapshot.error
            }
            report.check("inspect.trails.turnedOff", gone == true && off != null, "the Trails tab is gone: ${gone == true}. The open trail says: $off")
        } finally {
            settings.trailsEnabled = true
            service.trailsSettingChanged()
        }
        val back = withTimeoutOrNull(60_000) {
            while (contentNamed(toolWindow, "Trails") == null || host.snapshot().view?.timelinePending != false || host.snapshot().view?.partial == true) delay(300)
            true
        }
        report.check("inspect.trails.turnedOn", back == true, "the Trails tab is back, and the open trail shows \"${host.snapshot().view?.title}\" again")
    }

    private suspend fun trailText(host: TrailHost): String {
        val text = CompletableDeferred<String>()
        host.readText { text.complete(it) }
        return withTimeoutOrNull(5_000) { text.await() }.orEmpty()
    }

    /**
     * The plugin's settings page: where the IDE lists it, what it says about
     * a value the plugin could not use, that a value is kept, and that a new
     * path to the CLI takes effect in the open project. The settings are put
     * back as they were.
     */
    private suspend fun settingsPage(project: Project, checkpoints: Tree, report: Report) {
        val extension = Configurable.APPLICATION_CONFIGURABLE.extensionList.firstOrNull { it.id == "io.entire.explorer.settings" }
        report.check(
            "inspect.settings.registered",
            extension != null && extension.parentId == "tools",
            "id ${extension?.id}, under \"${extension?.parentId}\", named \"${extension?.getDisplayName()}\"",
        )
        val configurable = withContext(Dispatchers.EDT) { extension?.createConfigurable() } ?: return
        val settings = EntireSettings.getInstance()
        val before = settings.cliPath to settings.webBaseUrl
        val stored = Path.of(PathManager.getOptionsPath(), "entire.xml")
        val service = EntireService.getInstance(project)
        val manager = EntireService::class.java.getDeclaredField("manager").apply { isAccessible = true }.get(service) as RepoManager
        try {
            val panel = withContext(Dispatchers.EDT) { (configurable.createComponent() as DialogPanel).also { configurable.reset() } }
            val path = withContext(Dispatchers.EDT) { UIUtil.findComponentOfType(panel, TextFieldWithBrowseButton::class.java) }
            val url = withContext(Dispatchers.EDT) { UIUtil.findComponentsOfType(panel, JBTextField::class.java).firstOrNull { it !== path?.textField } }
            if (path == null || url == null) {
                report.fail("inspect.settings.fields", "the page has no path field or no address field")
                return
            }
            val labels = withContext(Dispatchers.EDT) { UIUtil.findComponentsOfType(panel, JLabel::class.java).mapNotNull { it.text }.filter { it.endsWith(":") } }
            report.info("inspect.settings.fields", "labels $labels, path \"${path.text}\", address \"${url.text}\", links go to ${manager.webBase()}")

            // An address the plugin would not build links from is refused.
            val refused = withContext(Dispatchers.EDT) {
                url.text = "entire.example"
                panel.validateAll()
            }
            report.check(
                "inspect.settings.address.refused",
                refused.any { !it.warning },
                "\"entire.example\" gets: ${refused.joinToString { (if (it.warning) "warning: " else "error: ") + it.message }}",
            )

            // Applying the page with that address stores nothing.
            val thrown = withContext(Dispatchers.EDT) {
                try {
                    configurable.apply()
                    null
                } catch (e: ConfigurationException) {
                    e
                }
            }
            report.check(
                "inspect.settings.address.notStored",
                thrown != null && settings.webBaseUrl == null,
                "applying it ${if (thrown == null) "was accepted" else "was refused with \"${thrown.localizedMessage}\""}, and the setting is ${settings.webBaseUrl}",
            )

            val accepted = withContext(Dispatchers.EDT) {
                url.text = "https://entire.example/"
                val found = panel.validateAll()
                if (found.none { !it.warning } && configurable.isModified) configurable.apply()
                found
            }
            saveSettings(ApplicationManager.getApplication(), true)
            val written = if (Files.exists(stored)) Files.readString(stored) else ""
            report.check(
                "inspect.settings.address.kept",
                accepted.isEmpty() && settings.webBaseUrl == "https://entire.example/" && manager.webBase() == "https://entire.example" &&
                    written.contains("https://entire.example/"),
                "the setting is \"${settings.webBaseUrl}\", links go to ${manager.webBase()}, and ${stored.fileName} holds it: ${written.contains("https://entire.example/")}",
            )

            // A path with no file at it is questioned, and not refused. Once applied, the project has no CLI to run.
            val rowsBefore = withContext(Dispatchers.EDT) { checkpoints.rowCount }
            val questioned = withContext(Dispatchers.EDT) {
                path.text = "/nonexistent/entire"
                // What the page says about a field as it is edited.
                val found = panel.validationsOnInput.values.flatten().mapNotNull { it.validate() }
                configurable.apply()
                found
            }
            val missing = withTimeoutOrNull(30_000) {
                while (withContext(Dispatchers.EDT) { checkpoints.rowCount > 0 || !emptyText(checkpoints).startsWith("The Entire CLI is not installed") }) delay(200)
                true
            }
            report.check(
                "inspect.settings.path.applied",
                questioned.any { it.warning } && questioned.none { !it.warning } && missing == true,
                "\"/nonexistent/entire\" gets: ${questioned.joinToString { (if (it.warning) "warning: " else "error: ") + it.message }}. " +
                    "The Checkpoints tab then went from $rowsBefore rows to: ${withContext(Dispatchers.EDT) { emptyText(checkpoints) }}",
            )

            // Opened again with that path stored, the page questions it before anything is typed.
            val reopened = withContext(Dispatchers.EDT) {
                val again = extension?.createConfigurable() ?: return@withContext null
                try {
                    val opened = again.createComponent() as DialogPanel
                    again.reset()
                    opened.validationsOnInput.keys.mapNotNull { field -> ComponentValidator.getInstance(field).orElse(null)?.validationInfo }
                } finally {
                    again.disposeUIResources()
                }
            }
            report.check(
                "inspect.settings.path.questionedOnOpen",
                reopened != null && reopened.any { it.warning },
                "a page opened with the path stored shows: ${reopened?.joinToString { (if (it.warning) "warning: " else "error: ") + it.message }}",
            )

            // Emptied, both settings are gone, and the project finds the CLI by itself again.
            withContext(Dispatchers.EDT) {
                path.text = ""
                url.text = ""
                configurable.apply()
            }
            val back = withTimeoutOrNull(60_000) {
                while (withContext(Dispatchers.EDT) { checkpoints.rowCount } == 0) delay(200)
                true
            }
            saveSettings(ApplicationManager.getApplication(), true)
            val after = if (Files.exists(stored)) Files.readString(stored) else ""
            report.check(
                "inspect.settings.cleared",
                back == true && settings.cliPath == null && settings.webBaseUrl == null && !after.contains("entire.example") && !after.contains("nonexistent"),
                "the Checkpoints tab has ${withContext(Dispatchers.EDT) { checkpoints.rowCount }} rows again, links go to ${manager.webBase()}, " +
                    "and ${stored.fileName} ${if (Files.exists(stored)) "holds neither value" else "is gone"}",
            )
        } finally {
            // Whatever a failed check left behind, the next start of this sandbox begins from what was set before.
            if (settings.cliPath != before.first || settings.webBaseUrl != before.second) {
                settings.cliPath = before.first
                settings.webBaseUrl = before.second
                service.settingsChanged()
            }
            withContext(Dispatchers.EDT) { configurable.disposeUIResources() }
        }
    }

    /**
     * Opens the IDE's Settings dialog the way the toolbar button does, and
     * paints it into a picture. The dialog is modal, so everything that looks
     * at it runs while it is open.
     */
    private suspend fun settingsDialog(project: Project, report: Report, picture: Path) {
        val whileModal = Dispatchers.EDT + ModalityState.any().asContextElement()
        Files.deleteIfExists(picture)
        ApplicationManager.getApplication().invokeLater { EntireService.getInstance(project).openSettings() }
        val dialog = withTimeoutOrNull(30_000) {
            var found: Dialog? = null
            while (found == null) {
                delay(300)
                found = withContext(whileModal) { Window.getWindows().filterIsInstance<Dialog>().firstOrNull { it.isShowing } }
            }
            found
        }
        if (dialog == null) {
            report.fail("inspect.settings.dialog", "no dialog opened within 30 s")
            return
        }
        // The dialog selects the page and lays it out after it opens.
        delay(3_000)
        val seen = withContext(whileModal) {
            val pane = (dialog as? RootPaneContainer)?.rootPane
            val labels = if (pane == null) emptyList() else UIUtil.findComponentsOfType(pane, JLabel::class.java).mapNotNull { it.text }
            if (pane != null) {
                val scale = JBUIScale.sysScale(pane).toDouble()
                val image = BufferedImage((pane.width * scale).toInt(), (pane.height * scale).toInt(), BufferedImage.TYPE_INT_RGB)
                val graphics = image.createGraphics()
                graphics.scale(scale, scale)
                pane.paint(graphics)
                graphics.dispose()
                ImageIO.write(image, "png", picture.toFile())
            }
            // Whether the page fits the dialog: a page wider than the space it is given hides the ends of its fields.
            val field = pane?.let { UIUtil.findComponentOfType(it, TextFieldWithBrowseButton::class.java) }
            val page = field?.let { SwingUtilities.getAncestorOfClass(DialogPanel::class.java, it) }
            val viewport = page?.let { SwingUtilities.getAncestorOfClass(JViewport::class.java, it) }
            if (field != null && page != null) {
                val right = SwingUtilities.convertPoint(field, field.width, 0, page).x
                report.check(
                    "inspect.settings.fits",
                    viewport == null || page.width <= viewport.width,
                    "the page is ${page.width} wide and would like ${page.preferredSize.width}, in a space ${viewport?.width} wide. " +
                        "The path field ends at $right",
                )
            }
            val wrapper = pane?.let { DialogWrapper.findInstance(it) }
            if (wrapper != null) wrapper.close(DialogWrapper.CANCEL_EXIT_CODE) else dialog.dispose()
            Triple(dialog.title, dialog.isModal, labels)
        }
        report.check(
            "inspect.settings.dialog",
            seen.third.contains("Path to Entire CLI:") && seen.third.contains("Entire web app URL:"),
            "the dialog \"${seen.first}\" (modal ${seen.second}) shows the page's two fields: " +
                "${seen.third.filter { it.endsWith(":") }}. Painted into $picture",
        )
    }

    /**
     * Opens the tool window again when something else took its place. A tab
     * follows a detail tab only while it is on screen, and the IDE can put
     * another tool window where this one was while the project is starting.
     */
    private suspend fun keepOnScreen(toolWindow: ToolWindow, tree: Tree, report: Report, before: String) {
        if (withContext(Dispatchers.EDT) { tree.isShowing }) return
        report.info("inspect.offScreen", "the tree was not on screen before $before, so the tool window was opened again")
        withTimeoutOrNull(20_000) {
            while (!withContext(Dispatchers.EDT) { tree.isShowing }) {
                withContext(Dispatchers.EDT) { toolWindow.activate(null) }
                delay(500)
            }
        }
    }

    /**
     * The button of an open tool window draws its icon in the button's
     * selected color. The IDE gets there by repainting the icon's light
     * variant with its standard colors replaced, so an icon that the IDE
     * cannot recolor stays as dark as the light variant is.
     */
    private suspend fun selectedIcon(toolWindow: ToolWindow, report: Report) = withContext(Dispatchers.EDT) {
        val icon = toolWindow.icon
        val wanted = UIManager.getColor("ToolWindow.Button.selectedForeground")
        if (icon == null || wanted == null) {
            report.info("inspect.icon.selected", "not checked: icon $icon, selected color $wanted")
            return@withContext
        }
        val drawn = paintedColor(toStrokeIcon(icon, wanted))
        val close = drawn != null && listOf(drawn.red - wanted.red, drawn.green - wanted.green, drawn.blue - wanted.blue).all { kotlin.math.abs(it) <= 12 }
        report.check(
            "inspect.icon.selected",
            close,
            "the icon is ${icon.iconWidth} by ${icon.iconHeight}, draws in ${hex(paintedColor(icon))} when the tool window is closed, " +
                "and in ${hex(drawn)} on the selected button, whose color for icons is ${hex(wanted)}",
        )
    }

    /** The average color of the pixels an icon covers fully, or null when it covers none. */
    private fun paintedColor(icon: Icon): Color? {
        val scale = 4
        val image = BufferedImage(icon.iconWidth * scale, icon.iconHeight * scale, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.scale(scale.toDouble(), scale.toDouble())
        icon.paintIcon(null, graphics, 0, 0)
        graphics.dispose()
        var red = 0L
        var green = 0L
        var blue = 0L
        var covered = 0L
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                if ((pixel ushr 24) < 250) continue
                red += (pixel shr 16) and 0xFF
                green += (pixel shr 8) and 0xFF
                blue += pixel and 0xFF
                covered += 1
            }
        }
        return if (covered == 0L) null else Color((red / covered).toInt(), (green / covered).toInt(), (blue / covered).toInt())
    }

    private fun hex(color: Color?): String = if (color == null) "nothing" else String.format("#%02X%02X%02X", color.red, color.green, color.blue)

    private suspend fun childTexts(tree: Tree, row: Int): List<String> = withContext(Dispatchers.EDT) {
        val parent = tree.getPathForRow(row)
        (row + 1 until tree.rowCount).takeWhile { tree.getPathForRow(it)?.parentPath == parent }.map { rowText(tree, it) }
    }

    private suspend fun pressEnter(tree: Tree, row: Int) = withContext(Dispatchers.EDT) {
        tree.setSelectionRow(row)
        tree.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
            ?.actionPerformed(ActionEvent(tree, ActionEvent.ACTION_PERFORMED, "enter"))
    }

    /**
     * Opens the row of a checkpoint that holds several sessions. The row
     * should then hold one row for each session, and the row of a session
     * should open the checkpoint on that session.
     */
    private suspend fun sessionsOfCheckpoint(project: Project, tree: Tree, report: Report) {
        val several = Regex("\\b\\d+ sessions\\b")
        val row = withContext(Dispatchers.EDT) { (0 until tree.rowCount).firstOrNull { several.containsMatchIn(rowText(tree, it)) } }
        if (row == null) {
            report.info("inspect.slots", "no row on show is of a checkpoint that holds several sessions")
            return
        }
        val text = withContext(Dispatchers.EDT) { rowText(tree, row) }
        withContext(Dispatchers.EDT) { tree.expandRow(row) }
        val started = System.nanoTime()
        val children = withTimeoutOrNull(30_000) {
            var found = childTexts(tree, row)
            while (found.isEmpty() || found.any { it.startsWith("Loading") }) {
                delay(200)
                found = childTexts(tree, row)
            }
            found
        }
        report.check(
            "inspect.slots.expand",
            children != null && children.size > 1,
            "\"${text.take(90)}\" holds ${children?.size} rows after ${elapsedMs(started)} ms: ${children?.joinToString(" | ") { it.take(70) }}",
        )
        if (children == null || children.size < 2) return

        // The second session's row, so that the panel has to leave the session it would open on by itself.
        val host = PanelFiles.getInstance(project).previewHost() ?: return
        pressEnter(tree, row + 2)
        val opened = withTimeoutOrNull(60_000) {
            var snapshot = host.snapshot()
            while (snapshot.header == null || !text.startsWith(snapshot.header?.title.orEmpty()) || snapshot.header?.selected != 1) {
                delay(200)
                snapshot = host.snapshot()
            }
            snapshot
        }
        val header = opened?.header ?: host.snapshot().header
        report.check(
            "inspect.slots.open",
            opened != null,
            "the panel shows \"${header?.title}\", session ${header?.selected?.plus(1)} of ${header?.sessions?.size}, " +
                "${opened?.turns} turns, and the selected row reads \"${selectedRowText(tree).take(70)}\"",
        )
        withContext(Dispatchers.EDT) { tree.collapseRow(row) }
    }

    /** Selects the Sessions tab, and writes down what it lists, what a session holds, and what opening one shows. */
    private suspend fun sessionsTab(project: Project, toolWindow: ToolWindow, report: Report) {
        val content = contentNamed(toolWindow, "Sessions")
        if (content == null) {
            report.fail("inspect.sessions.tab", "the tool window has no second tab")
            return
        }
        // The plugin's log is read afterwards, to see that nothing about sessions ran before this line.
        Logger.getInstance("#io.entire.spikes").info("ENTIRE-SPIKE selecting the Sessions tab")
        withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.select("Sessions") }
        val tree = withTimeoutOrNull(15_000) {
            var found: Tree? = null
            while (found == null) {
                found = withContext(Dispatchers.EDT) { UIUtil.findComponentOfType(content, Tree::class.java) }
                if (found == null) delay(200)
            }
            found
        } ?: return
        val started = System.nanoTime()
        val onScreen = withTimeoutOrNull(30_000) {
            while (!withContext(Dispatchers.EDT) { tree.isShowing }) {
                withContext(Dispatchers.EDT) { toolWindow.activate(null) }
                delay(500)
            }
            true
        }
        report.check("inspect.sessions.onScreen", onScreen == true, "the Sessions tree was on screen after ${elapsedMs(started)} ms")
        if (onScreen != true) return
        val settled = withTimeoutOrNull(90_000) {
            while (true) {
                val (rows, empty) = withContext(Dispatchers.EDT) { tree.rowCount to emptyText(tree) }
                if (rows > 0 || (empty.isNotEmpty() && !empty.startsWith("Loading"))) break
                delay(200)
            }
            true
        }
        report.check("inspect.sessions.leftLoading", settled == true, "after ${elapsedMs(started)} ms")
        // Names arrive after the rows: from Entire for a repository it hosts, and from the transcripts otherwise.
        delay(12_000)
        report.info("inspect.sessions.tabName", withContext(Dispatchers.EDT) { tabsOf(toolWindow)?.title("Sessions").orEmpty() })
        describe(tree, report, "inspect.sessions")
        rowExtras(project, toolWindow, tree, report, "inspect.sessions.row", openWeb = true)

        val sessionRow = withContext(Dispatchers.EDT) {
            (0 until tree.rowCount).firstOrNull { row -> tree.getPathForRow(row)?.let { !tree.model.isLeaf(it.lastPathComponent) && it.pathCount == 2 } == true }
        } ?: return
        val sessionText = withContext(Dispatchers.EDT) { rowText(tree, sessionRow) }
        withContext(Dispatchers.EDT) { tree.expandRow(sessionRow) }
        // Opening a session asks Entire for its checkpoints when it counts more than the clone knows.
        delay(8_000)
        val children = withContext(Dispatchers.EDT) {
            val parent = tree.getPathForRow(sessionRow)
            (sessionRow + 1 until tree.rowCount).takeWhile { tree.getPathForRow(it)?.parentPath == parent }.map { rowText(tree, it) }
        }
        report.check(
            "inspect.sessions.expand",
            children.isNotEmpty() && children.none { it.startsWith("Loading") },
            "\"${sessionText.take(90)}\" holds ${children.size} rows, first \"${children.firstOrNull()?.take(110)}\"",
        )

        withContext(Dispatchers.EDT) {
            tree.setSelectionRow(sessionRow)
            tree.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
                ?.actionPerformed(ActionEvent(tree, ActionEvent.ACTION_PERFORMED, "enter"))
        }
        val host = PanelFiles.getInstance(project).previewHost() ?: return
        val opened = withTimeoutOrNull(90_000) {
            var snapshot = host.snapshot()
            while (snapshot.error == null && snapshot.header?.mode != DetailMode.SESSION) {
                delay(200)
                snapshot = host.snapshot()
            }
            snapshot
        }
        val header = opened?.header
        report.check(
            "inspect.sessions.openSession",
            header?.mode == DetailMode.SESSION,
            "title \"${header?.title?.take(80)}\", newest checkpoint ${header?.checkpointId}, transcript ${opened?.transcript?.source} with ${opened?.turns} turns " +
                "from turn ${opened?.transcript?.start}, place ${header?.position}, links to entire.com ${header?.canOpenOnWeb}, error ${opened?.error}",
        )

        if (children.size < 2) return
        // A checkpoint under the session, then Previous: the Sessions tab should select the earlier checkpoint under the same session.
        withContext(Dispatchers.EDT) {
            tree.setSelectionRow(sessionRow + 1)
            tree.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
                ?.actionPerformed(ActionEvent(tree, ActionEvent.ACTION_PERFORMED, "enter"))
        }
        val onCheckpoint = withTimeoutOrNull(60_000) {
            var snapshot = host.snapshot()
            while (snapshot.error == null && snapshot.header?.mode != DetailMode.CHECKPOINT) {
                delay(200)
                snapshot = host.snapshot()
            }
            snapshot
        }
        if (onCheckpoint?.header?.position?.hasPrevious != true) {
            report.info("inspect.sessions.selectionFollows", "the newest checkpoint of the session has none before it to step to: ${onCheckpoint?.header?.position}, error ${onCheckpoint?.error}")
            return
        }
        val from = onCheckpoint.header?.checkpointId
        host.receive(parseJson("""{"type":"stepSession","direction":"previous"}"""))
        val moved = withTimeoutOrNull(60_000) {
            var snapshot = host.snapshot()
            while (snapshot.header == null || snapshot.header?.checkpointId == from) {
                delay(200)
                snapshot = host.snapshot()
            }
            snapshot
        }
        val wantedRow = sessionRow + 2
        val selectedRow = withTimeoutOrNull(15_000) {
            var row = withContext(Dispatchers.EDT) { tree.selectionRows?.firstOrNull() ?: -1 }
            while (row != wantedRow) {
                delay(200)
                row = withContext(Dispatchers.EDT) { tree.selectionRows?.firstOrNull() ?: -1 }
            }
            row
        }
        report.check(
            "inspect.sessions.selectionFollows",
            selectedRow == wantedRow,
            "after Previous the tab shows checkpoint ${moved?.header?.checkpointId} (${moved?.header?.position?.ordinal} of ${moved?.header?.position?.total}), " +
                "and the Sessions tab selects \"${selectedRowText(tree).take(100)}\"",
        )
    }

    /** Opens the first row the way the Enter key does, and writes down what the panel then holds and draws. */
    private suspend fun openFirstRow(project: Project, toolWindow: ToolWindow, tree: Tree, report: Report) {
        val rows = withContext(Dispatchers.EDT) { tree.rowCount }
        if (rows == 0) return
        withContext(Dispatchers.EDT) {
            tree.setSelectionRow(0)
            tree.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
                ?.actionPerformed(ActionEvent(tree, ActionEvent.ACTION_PERFORMED, "enter"))
        }
        val files = PanelFiles.getInstance(project)
        val host = withTimeoutOrNull(20_000) {
            var found = files.previewHost()
            while (found == null) {
                delay(200)
                found = files.previewHost()
            }
            found
        }
        if (host == null) {
            report.fail("inspect.panel.opened", "no panel after Enter on the first row")
            return
        }
        val started = System.nanoTime()
        val first = withTimeoutOrNull(60_000) {
            var snapshot = host.snapshot()
            while (snapshot.header == null && snapshot.error == null) {
                delay(200)
                snapshot = host.snapshot()
            }
            snapshot
        }
        val header = first?.header
        report.check(
            "inspect.panel.loaded",
            header != null,
            "after ${elapsedMs(started)} ms: title \"${header?.title}\", checkpoint ${header?.checkpointId}, commit ${header?.commit?.shortSha}, " +
                "sessions ${header?.sessions?.size}, files ${header?.files?.size}, place ${header?.position?.ordinal} of ${header?.position?.total}, " +
                "author ${header?.promptAuthor}, transcript ${first?.transcript?.source} with ${first?.turns} turns from turn ${first?.transcript?.start}, " +
                "error ${first?.error}",
        )
        val tab = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).selectedEditor?.file?.presentableName }
        report.info("inspect.panel.tabTitle", tab.orEmpty())

        val text = withTimeoutOrNull(30_000) {
            var drawn = pageText(host)
            while (!drawn.contains("Files") && !drawn.contains("Transcript") && drawn.length < 400) {
                delay(300)
                drawn = pageText(host)
            }
            drawn
        }
        report.check("inspect.panel.pageDrawn", !text.isNullOrBlank(), "${text?.length} characters: ${text?.replace(Regex("\\s+"), " ")?.take(420)}")

        commitDiff(project, host, report)
        rawTranscript(project, host, report)

        if (header?.position?.hasPrevious == true) {
            keepOnScreen(toolWindow, tree, report, "pressing Previous")
            host.receive(parseJson("""{"type":"stepSession","direction":"previous"}"""))
            val moved = withTimeoutOrNull(60_000) {
                var snapshot = host.snapshot()
                while (snapshot.header == null || snapshot.header?.checkpointId == header.checkpointId) {
                    delay(200)
                    snapshot = host.snapshot()
                }
                snapshot
            }
            val wanted = moved?.header?.title.orEmpty()
            val selected = withTimeoutOrNull(10_000) {
                var text = selectedRowText(tree)
                while (!text.startsWith(wanted)) {
                    delay(200)
                    text = selectedRowText(tree)
                }
                text
            }
            report.check(
                "inspect.panel.selectionFollows",
                selected != null && wanted.isNotEmpty(),
                "the tab shows \"$wanted\", and the selected row reads \"${selected ?: selectedRowText(tree)}\"" +
                    ", with the tree on screen: ${withContext(Dispatchers.EDT) { tree.isShowing }}",
            )
            report.check(
                "inspect.panel.previous",
                moved?.header != null,
                "now checkpoint ${moved?.header?.checkpointId}, place ${moved?.header?.position?.ordinal} of ${moved?.header?.position?.total}, " +
                    "${moved?.turns} turns from turn ${moved?.transcript?.start}",
            )
        }
        // Let the page draw what it was last sent before a picture is taken.
        delay(1_500)
    }

    /** A panel tab that was open when the IDE last closed should be open again, and showing the same thing. */
    private suspend fun restoredTab(project: Project, report: Report) {
        val file = withTimeoutOrNull(12_000) {
            var found: EntirePanelFile? = null
            while (found == null) {
                found = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFiles.filterIsInstance<EntirePanelFile>().firstOrNull() }
                if (found == null) delay(250)
            }
            found
        }
        if (file == null) {
            report.info("inspect.restoredTab", "no panel tab was open when the IDE started")
            return
        }
        val host = PanelFiles.getInstance(project).host(file)
        val snapshot = withTimeoutOrNull(60_000) {
            var current = host?.snapshot()
            while (current != null && current.header == null && current.error == null) {
                delay(200)
                current = host?.snapshot()
            }
            current
        }
        val text = if (host != null) {
            withTimeoutOrNull(30_000) {
                var drawn = pageText(host)
                while (!drawn.contains("TRANSCRIPT") && !drawn.contains("Transcript")) {
                    delay(300)
                    drawn = pageText(host)
                }
                drawn
            }
        } else {
            null
        }
        report.check(
            "inspect.restoredTab",
            snapshot?.header != null && !text.isNullOrBlank(),
            "slot ${file.slot}, tab \"${file.presentableName}\", checkpoint ${snapshot?.header?.checkpointId}, ${snapshot?.turns} turns, " +
                "page drew ${text?.length} characters, error ${snapshot?.error}",
        )
    }

    /** The panel's Show Commit Diff button, pressed the way the page presses it. */
    private suspend fun commitDiff(project: Project, host: PanelHost, report: Report) {
        val before = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFiles.toSet() }
        host.receive(parseJson("""{"type":"showCommitDiff"}"""))
        val opened = withTimeoutOrNull(20_000) {
            var added = emptyList<VirtualFile>()
            while (added.isEmpty()) {
                delay(250)
                added = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFiles.filter { it !in before } }
            }
            added
        }
        report.check(
            "inspect.panel.commitDiff",
            opened != null,
            opened?.joinToString { "${it.presentableName} (${it.javaClass.simpleName})" } ?: "no tab opened. The diff may be in a window of its own",
        )
        opened?.forEach { file -> withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).closeFile(file) } }
    }

    /** The panel's Open Raw Transcript button. The CLI writes the transcript to a file, and the IDE opens the file. */
    private suspend fun rawTranscript(project: Project, host: PanelHost, report: Report) {
        val before = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFiles.toSet() }
        host.receive(parseJson("""{"type":"openRawTranscript"}"""))
        val opened = withTimeoutOrNull(90_000) {
            var added = emptyList<VirtualFile>()
            while (added.isEmpty()) {
                delay(250)
                added = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFiles.filter { it !in before } }
            }
            added
        }
        val file = opened?.firstOrNull()
        report.check(
            "inspect.panel.rawTranscript",
            file != null && file.name.endsWith(".jsonl"),
            "${file?.name}, ${file?.length} bytes, writable ${file?.isWritable}, in ${file?.parent?.name}",
        )
        opened?.forEach { added -> withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).closeFile(added) } }
    }

    /**
     * Steps from a checkpoint near the end of the list to the one before it in
     * its session, whose row is further down than the list shows. The list has
     * to grow for the row to be selected.
     */
    private suspend fun followBeyondList(project: Project, tree: Tree, report: Report) {
        val host = PanelFiles.getInstance(project).previewHost() ?: return
        val loadMore = withContext(Dispatchers.EDT) { (0 until tree.rowCount).lastOrNull { rowText(tree, it).startsWith("Load more") } } ?: return
        for (row in (loadMore - 1) downTo maxOf(0, loadMore - 12)) {
            val label = withContext(Dispatchers.EDT) { rowText(tree, row) }
            withContext(Dispatchers.EDT) {
                tree.setSelectionRow(row)
                tree.getActionForKeyStroke(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0))
                    ?.actionPerformed(ActionEvent(tree, ActionEvent.ACTION_PERFORMED, "enter"))
            }
            val opened = withTimeoutOrNull(60_000) {
                var snapshot = host.snapshot()
                while (snapshot.header == null || !label.startsWith(snapshot.header?.title.orEmpty())) {
                    delay(200)
                    snapshot = host.snapshot()
                }
                snapshot
            } ?: continue
            if (opened.header?.position?.hasPrevious != true) continue

            val rowsBefore = withContext(Dispatchers.EDT) { tree.rowCount }
            host.receive(parseJson("""{"type":"stepSession","direction":"previous"}"""))
            val moved = withTimeoutOrNull(60_000) {
                var snapshot = host.snapshot()
                while (snapshot.header == null || snapshot.header?.checkpointId == opened.header?.checkpointId) {
                    delay(200)
                    snapshot = host.snapshot()
                }
                snapshot
            }
            val wanted = moved?.header?.title.orEmpty()
            val selected = withTimeoutOrNull(15_000) {
                var text = selectedRowText(tree)
                while (!text.startsWith(wanted)) {
                    delay(200)
                    text = selectedRowText(tree)
                }
                text
            }
            val (selectedRow, rowsAfter) = withContext(Dispatchers.EDT) { (tree.selectionRows?.firstOrNull() ?: -1) to tree.rowCount }
            report.check(
                "inspect.panel.selectionFollowsBeyondList",
                selected != null && wanted.isNotEmpty() && selectedRow >= loadMore,
                "opened row ${row + 1} of $loadMore shown, pressed Previous, the tab shows \"$wanted\", " +
                    "the selected row is now row ${selectedRow + 1}, and the list went from $rowsBefore to $rowsAfter rows",
            )
            return
        }
        report.info("inspect.panel.selectionFollowsBeyondList", "no row near the end of the list had an earlier checkpoint to step to")
    }

    private suspend fun selectedRowText(tree: Tree): String =
        withContext(Dispatchers.EDT) { tree.selectionRows?.firstOrNull()?.let { rowText(tree, it) }.orEmpty() }

    private suspend fun pageText(host: PanelHost): String {
        val text = CompletableDeferred<String>()
        host.readText { text.complete(it) }
        return withTimeoutOrNull(5_000) { text.await() }.orEmpty()
    }

    private suspend fun describe(tree: Tree, report: Report, key: String) {
        val (count, empty, rows) = withContext(Dispatchers.EDT) {
            val count = tree.rowCount
            val shown = (0 until minOf(count, 5)).map { rowText(tree, it) } +
                (if (count > 5) listOf("…", rowText(tree, count - 1)) else emptyList())
            Triple(count, emptyText(tree), shown)
        }
        report.info("$key.rows", count.toString())
        if (count == 0) report.info("$key.emptyText", empty)
        rows.forEachIndexed { index, text -> report.info("$key.row${index + 1}", text) }
    }

    /** What the tab says in place of rows. The panel gives the same lines to a screen reader. */
    private fun emptyText(tree: Tree): String = tree.accessibleContext.accessibleDescription.orEmpty()

    /** The text of a row as the renderer draws it. */
    private fun rowText(tree: Tree, row: Int): String {
        val value = tree.getPathForRow(row)?.lastPathComponent ?: return ""
        val component = tree.cellRenderer.getTreeCellRendererComponent(tree, value, false, tree.isExpanded(row), tree.model.isLeaf(value), row, false)
        return (component as? SimpleColoredComponent)?.getCharSequence(false)?.toString() ?: value.toString()
    }
}
