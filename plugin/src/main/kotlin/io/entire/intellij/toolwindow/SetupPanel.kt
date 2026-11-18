package io.entire.intellij.toolwindow

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.EnvironmentUtil
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import io.entire.app.setup.SetupFacts
import io.entire.app.setup.SetupHost
import io.entire.app.setup.SetupOptions
import io.entire.app.setup.SetupStep
import io.entire.app.setup.StepId
import io.entire.app.setup.StepState
import io.entire.app.setup.setupSteps
import io.entire.core.CallResult
import io.entire.core.cli.AGENT_COMMANDS
import io.entire.core.cli.KNOWN_AGENTS
import io.entire.core.setup.Os
import io.entire.core.setup.SetupCommand
import io.entire.core.setup.isSetupCommand
import io.entire.intellij.EntireBundle
import io.entire.intellij.EntireService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.intellij.openapi.application.EDT

/**
 * The Setup tab: what is missing for Entire in the repository, and a button
 * for each step that runs it.
 *
 * Each step shows what it changes and its exact command before the click.
 * Nothing runs until a step's button is clicked, and one step runs at a time.
 * The command's output streams into a console under the steps. When it ends,
 * the project is read again, and every step says how it stands now.
 *
 * Reading what is missing only reads. The commands that write go through
 * [isSetupCommand], which accepts the setup commands and nothing else.
 */
class SetupPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()), Disposable {
    private val log = logger<SetupPanel>()
    private val service = EntireService.getInstance(project)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)

    private val host = SetupHost(
        os = when {
            SystemInfo.isWindows -> Os.WINDOWS
            SystemInfo.isMac -> Os.MAC
            else -> Os.LINUX
        },
        brew = if (SystemInfo.isMac) listOf("/opt/homebrew/bin/brew", "/usr/local/bin/brew").firstOrNull { File(it).canExecute() } else null,
        homeDir = System.getProperty("user.home"),
    )

    private val stepsPanel = JPanel().also {
        it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
        it.border = JBUI.Borders.empty(8, 12)
    }
    private val repoChoice = ComboBox<Pair<String, String>>()
    private val checkAgain = JButton(EntireBundle.message("setup.checkAgain"))
    private val close = ActionLink(EntireBundle.message("setup.close")) { onClose() }

    private val console: ConsoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
    private val stop = JButton(EntireBundle.message("setup.stop"), AllIcons.Actions.Suspend)
    private val consolePanel = JPanel(BorderLayout())
    private val splitter = OnePixelSplitter(true, 0.6f)

    private var facts: SetupFacts? = null
    private var options = SetupOptions()

    /** The repository the options were chosen for. They are chosen again for another one. */
    private var optionsFor: String? = null
    private var checking = false

    /** A check was asked for while one ran. It runs once that one ends, so it reads what changed since. */
    private var checkAgainAfter = false
    private var running: ProcessHandler? = null
    private var runningStep: StepId? = null

    /** The step that was asked for, which the tab scrolls to once it is drawn. */
    private var focusStep: StepId? = null
    private val stepComponents = HashMap<StepId, JComponent>()

    /** Called when the reader closes the tab. */
    var onClose: () -> Unit = {}

    init {
        Disposer.register(parent, this)
        Disposer.register(this, console)

        val header = JPanel().also {
            it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
            it.border = JBUI.Borders.empty(8, 12, 0, 12)
        }
        val titleRow = JPanel(BorderLayout()).also { it.isOpaque = false }
        titleRow.add(JBLabel(EntireBundle.message("setup.title")).also { it.font = JBUI.Fonts.label().asBold().biggerOn(2f) }, BorderLayout.WEST)
        titleRow.add(close, BorderLayout.EAST)
        header.add(left(titleRow))
        header.add(left(text(EntireBundle.message("setup.intro"), muted = true)))
        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(4))).also { it.isOpaque = false }
        repoChoice.renderer = SimpleListCellRenderer.create("") { it.second }
        repoChoice.addActionListener { if (!checking) check() }
        repoChoice.isVisible = false
        controls.add(repoChoice)
        controls.add(Box.createHorizontalStrut(JBUI.scale(6)))
        checkAgain.addActionListener { check() }
        controls.add(checkAgain)
        header.add(left(controls))

        val top = JPanel(BorderLayout())
        top.add(header, BorderLayout.NORTH)
        // The steps keep to the top of their space, and do not spread over it.
        val stepsTop = JPanel(BorderLayout()).also { it.add(stepsPanel, BorderLayout.NORTH) }
        top.add(ScrollPaneFactory.createScrollPane(stepsTop, true), BorderLayout.CENTER)

        stop.isEnabled = false
        stop.addActionListener { running?.destroyProcess() }
        val consoleHeader = JPanel(BorderLayout()).also { it.border = JBUI.Borders.empty(4, 12) }
        consoleHeader.add(JBLabel(EntireBundle.message("setup.output")).also { it.font = JBUI.Fonts.label().asBold() }, BorderLayout.WEST)
        consoleHeader.add(stop, BorderLayout.EAST)
        consolePanel.add(consoleHeader, BorderLayout.NORTH)
        consolePanel.add(console.component, BorderLayout.CENTER)

        splitter.firstComponent = top
        add(splitter, BorderLayout.CENTER)
    }

    /** Shows the tab at a step, or at the first step to do. */
    fun focus(step: StepId?) {
        focusStep = step
        check()
    }

    /** Reads again what is set up, and draws the steps. It only reads. */
    fun check() {
        if (checking) {
            checkAgainAfter = true
            return
        }
        checking = true
        checkAgain.isEnabled = false
        val chosen = repoChoice.selectedItem as? Pair<*, *>
        scope.launch {
            try {
                val repos = service.setupRepos()
                repoChoice.isVisible = repos.size > 1
                if (repos != (0 until repoChoice.itemCount).map { repoChoice.getItemAt(it) }) {
                    repoChoice.removeAllItems()
                    repos.forEach { repoChoice.addItem(it) }
                    repos.firstOrNull { it.first == chosen?.first }?.let { repoChoice.selectedItem = it }
                }
                val root = (repoChoice.selectedItem as? Pair<*, *>)?.first as? String
                showChecking()
                val read = service.setupFacts(root)
                facts = read
                chooseOptions(read)
                render(read)
            } finally {
                checking = false
                checkAgain.isEnabled = running == null
                if (checkAgainAfter) {
                    checkAgainAfter = false
                    check()
                }
            }
        }
    }

    /**
     * The choices a repository starts with: the agents that have hooks in it
     * already, or else the ones whose command is on this machine's PATH.
     */
    private fun chooseOptions(read: SetupFacts) {
        if (optionsFor == read.root) return
        optionsFor = read.root
        val installed = (read.agents as? CallResult.Ok)?.value?.filter { it.installed }?.map { it.name }.orEmpty()
        val onPath = AGENT_COMMANDS.filterValues { onPath(it) }.keys
        options = options.copy(agents = (installed.ifEmpty { onPath }).toSet())
    }

    private fun onPath(command: String): Boolean {
        val path = EnvironmentUtil.getValue("PATH") ?: return false
        val names = if (SystemInfo.isWindows) listOf("$command.exe", "$command.cmd") else listOf(command)
        return path.split(File.pathSeparatorChar).any { dir -> dir.isNotEmpty() && names.any { File(dir, it).canExecute() } }
    }

    private fun render(read: SetupFacts) {
        stepsPanel.removeAll()
        stepComponents.clear()
        val steps = setupSteps(read, options, host)
        for (step in steps) {
            val component = stepView(read, step)
            stepComponents[step.id] = component
            stepsPanel.add(component)
            stepsPanel.add(Box.createVerticalStrut(JBUI.scale(10)))
        }
        stepsPanel.revalidate()
        stepsPanel.repaint()
        val wanted = focusStep ?: steps.firstOrNull { it.state == StepState.TODO }?.id
        focusStep = null
        val target = wanted?.let { stepComponents[it] } ?: return
        // Once the steps are laid out, the step is scrolled into view.
        ApplicationManager.getApplication().invokeLater { target.scrollRectToVisible(Rectangle(0, 0, target.width, target.height)) }
    }

    private fun stepView(read: SetupFacts, step: SetupStep): JComponent {
        val panel = JPanel().also {
            it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
            it.isOpaque = false
            it.alignmentX = LEFT_ALIGNMENT
        }
        val stateText = EntireBundle.message(
            when (step.state) {
                StepState.DONE -> "setup.state.done"
                StepState.TODO -> "setup.state.todo"
                StepState.BLOCKED -> "setup.state.blocked"
                StepState.NOT_APPLICABLE -> "setup.state.notApplicable"
                StepState.CHECKING -> "setup.state.checking"
            },
        )
        val icon = when {
            runningStep == step.id -> AnimatedIcon.Default.INSTANCE
            step.state == StepState.DONE -> AllIcons.General.InspectionsOK
            step.state == StepState.CHECKING -> AnimatedIcon.Default.INSTANCE
            step.state == StepState.TODO -> AllIcons.General.Information
            else -> null
        }
        val title = JBLabel("${step.id.ordinal + 1}. ${step.title}", icon, JBLabel.LEFT).also { it.font = JBUI.Fonts.label().asBold() }
        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).also { it.isOpaque = false }
        titleRow.add(title)
        titleRow.add(Box.createHorizontalStrut(JBUI.scale(8)))
        titleRow.add(JBLabel(stateText).also { it.foreground = UIUtil.getContextHelpForeground() })
        panel.add(left(titleRow))
        val quiet = step.state == StepState.BLOCKED || step.state == StepState.NOT_APPLICABLE
        panel.add(left(text(step.status, muted = quiet)))
        if (step.state != StepState.TODO) return panel

        panel.add(left(text(step.about, muted = true)))
        panel.add(left(text(EntireBundle.message("setup.changes", step.changes), muted = true)))
        options(read, step)?.let { panel.add(left(it)) }
        if (step.commands.isNotEmpty()) {
            val commands = JBTextArea(step.commands.joinToString("\n") { "$ ${it.display}" }).also {
                it.isEditable = false
                it.font = Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().size)
                it.lineWrap = true
                it.border = JBUI.Borders.empty(6, 8)
                it.background = JBColor.namedColor("EditorPane.inactiveBackground", UIUtil.getPanelBackground())
            }
            panel.add(Box.createVerticalStrut(JBUI.scale(4)))
            panel.add(left(commands))
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(6))).also { it.isOpaque = false }
        val run = JButton(step.action).also {
            it.isEnabled = step.commands.isNotEmpty() && running == null
            it.addActionListener { run(step) }
        }
        buttons.add(run)
        if (step.id == StepId.CLI) {
            buttons.add(Box.createHorizontalStrut(JBUI.scale(10)))
            buttons.add(ActionLink(EntireBundle.message("link.setCliPath")) { service.openSettings() })
        }
        panel.add(left(buttons))
        return panel
    }

    /** The choices a step offers. Each one changes a flag of its command, which redraws at once. */
    private fun options(read: SetupFacts, step: SetupStep): JComponent? {
        val box = JPanel().also {
            it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
            it.isOpaque = false
            it.border = JBUI.Borders.empty(4, 0)
        }
        fun option(label: String, selected: Boolean, change: (Boolean) -> SetupOptions) {
            box.add(
                left(
                    JBCheckBox(label, selected).also { check ->
                        check.isOpaque = false
                        check.isEnabled = running == null
                        check.addActionListener {
                            options = change(check.isSelected)
                            // The facts last read, and not the ones this box was drawn with: a check may have ended since.
                            render(facts ?: read)
                        }
                    },
                ),
            )
        }
        when (step.id) {
            StepId.ENABLE -> {
                box.add(left(JBLabel(EntireBundle.message("setup.agents"))))
                val agents = (read.agents as? CallResult.Ok)?.value?.map { it.name } ?: KNOWN_AGENTS
                for (agent in agents) {
                    option(agent, agent in options.agents) { on -> options.copy(agents = if (on) options.agents + agent else options.agents - agent) }
                }
                option(EntireBundle.message("setup.telemetry"), options.telemetry) { options.copy(telemetry = it) }
            }
            StepId.LOGIN -> option(EntireBundle.message("setup.deviceLogin"), options.deviceLogin) { options.copy(deviceLogin = it) }
            StepId.BRAIN -> {
                option(EntireBundle.message("setup.daemon"), options.daemon) { options.copy(daemon = it) }
                option(EntireBundle.message("setup.backfill"), options.backfill) { options.copy(backfill = it) }
            }
            else -> return null
        }
        return box
    }

    /** Runs a step's commands one after another, in a console under the steps, and stops at the first that fails. */
    private fun run(step: SetupStep) {
        if (running != null || step.commands.isEmpty()) return
        // Only the setup commands run here, and only the CLI that the plugin found runs them.
        val refused = step.commands.firstOrNull { !isSetupCommand(it, service.cliPath, host.brew) }
        if (refused != null) {
            log.warn("Entire setup refused: ${refused.display}")
            return
        }
        if (splitter.secondComponent == null) splitter.secondComponent = consolePanel
        console.clear()
        runningStep = step.id
        facts?.let { render(it) }
        checkAgain.isEnabled = false
        runCommand(step, 0)
    }

    private fun runCommand(step: SetupStep, index: Int) {
        val command = step.commands[index]
        val directory = if (step.id == StepId.CLI) host.homeDir else facts?.root ?: host.homeDir
        val line = GeneralCommandLine(listOf(command.program) + command.args)
            .withWorkDirectory(directory)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.NONE)
            // The IDE's own environment lacks the login shell's PATH when it was started from the desktop.
            .withEnvironment(EnvironmentUtil.getEnvironmentMap() + mapOf("GIT_TERMINAL_PROMPT" to "0"))
        console.print("$ ${command.display}\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        val handler = try {
            KillableColoredProcessHandler(line)
        } catch (e: ExecutionException) {
            console.print("${e.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            log.warn("Entire setup could not start: ${command.display}: ${e.message}")
            ended(step, command, failed = true, exitCode = -1)
            return
        }
        running = handler
        stop.isEnabled = true
        log.info("Entire setup runs: ${command.display}")
        console.attachToProcess(handler)
        handler.addProcessListener(
            object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    ApplicationManager.getApplication().invokeLater {
                        log.info("Entire setup ran: ${command.display} (exit ${event.exitCode})")
                        if (event.exitCode == 0 && index + 1 < step.commands.size) {
                            runCommand(step, index + 1)
                        } else {
                            ended(step, command, failed = event.exitCode != 0, exitCode = event.exitCode)
                        }
                    }
                }
            },
        )
        handler.startNotify()
    }

    /** A step ended. The project is read again, so that the steps and the other tabs show what changed. */
    private fun ended(step: SetupStep, last: SetupCommand, failed: Boolean, exitCode: Int) {
        running = null
        runningStep = null
        stop.isEnabled = false
        console.print(
            if (failed) EntireBundle.message("setup.failed", last.display, exitCode) + "\n" else EntireBundle.message("setup.succeeded") + "\n",
            if (failed) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.SYSTEM_OUTPUT,
        )
        // A browser that did not open, or a login that needs a terminal, is helped by the code instead.
        if (failed && step.id == StepId.LOGIN) options = options.copy(deviceLogin = true)
        focusStep = step.id
        // The tool window reloads before the steps are read again. Until then the steps say they are being checked,
        // so none reads as still to do, and no button offers the step again.
        showChecking()
        service.setupStepEnded { check() }
    }

    /** Draws the steps whose facts are about to be read as being checked, with nothing to run. */
    private fun showChecking() {
        facts?.let { known -> render(known.copy(login = null, enabled = null, graph = null, brain = null)) }
    }

    private fun text(content: String, muted: Boolean): JBTextArea =
        JBTextArea(content).also {
            it.isEditable = false
            it.lineWrap = true
            it.wrapStyleWord = true
            it.isOpaque = false
            it.border = JBUI.Borders.empty(1, 0)
            it.font = JBUI.Fonts.label()
            if (muted) it.foreground = UIUtil.getContextHelpForeground()
        }

    private fun <T : JComponent> left(component: T): T = component.also { it.alignmentX = LEFT_ALIGNMENT }

    override fun dispose() {
        running?.destroyProcess()
        scope.cancel()
    }
}
