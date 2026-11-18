package io.entire.app.setup

import io.entire.core.CallResult
import io.entire.core.FailureKind
import io.entire.core.brain.BrainStatus
import io.entire.core.brain.hasBrain
import io.entire.core.cli.AgentChoice
import io.entire.core.cli.AuthStatus
import io.entire.core.cli.MIN_CLI_VERSION_TEXT
import io.entire.core.setup.Os
import io.entire.core.setup.SetupCommand
import io.entire.core.setup.brainConnect
import io.entire.core.setup.brainSetup
import io.entire.core.setup.enable
import io.entire.core.setup.graphSetup
import io.entire.core.setup.installCli
import io.entire.core.setup.installPlugin
import io.entire.core.setup.login

/** The steps of the Setup tab, in the order they are taken. */
enum class StepId { CLI, LOGIN, ENABLE, GRAPH, BRAIN, HOSTED }

enum class StepState {
    DONE,
    TODO,

    /** Another step has to be done first. */
    BLOCKED,

    /** The step does not apply to this repository. */
    NOT_APPLICABLE,

    /** What the step depends on is being read. */
    CHECKING,
}

/** How the CLI stands. */
enum class CliState { FOUND, MISSING, TOO_OLD }

/**
 * What the plugin read about this machine and one repository. A read that
 * is under way is null. A read that failed holds its failure.
 */
data class SetupFacts(
    val cliState: CliState,
    val cliPath: String? = null,
    val cliVersion: String? = null,
    /** The path set on the settings page, when one is. */
    val configuredPath: String? = null,
    /** The repository the steps after the login are for. Null when the project has none. */
    val root: String? = null,
    val repoName: String? = null,
    /** The repository's origin uses the entire:// scheme. */
    val hosted: Boolean = false,
    val login: CallResult<AuthStatus>? = null,
    val enabled: Boolean? = null,
    val agents: CallResult<List<AgentChoice>>? = null,
    val graph: CallResult<String>? = null,
    val brain: CallResult<BrainStatus>? = null,
    /** The repository has the agent guide that `entire graph setup` and `entire brain setup` write. */
    val agentGuide: Boolean = false,
)

/** The choices the Setup tab offers. Each one becomes a flag of a command. */
data class SetupOptions(
    val agents: Set<String> = emptySet(),
    val telemetry: Boolean = true,
    /** Distill past sessions into memories. It spends agent tokens. */
    val backfill: Boolean = false,
    /** Keep the brain current with one background watcher for this machine. */
    val daemon: Boolean = true,
    /** Log in with a code entered in the browser, for a CLI that will not open one. */
    val deviceLogin: Boolean = false,
)

/** One step as the Setup tab draws it. */
data class SetupStep(
    val id: StepId,
    val title: String,
    val state: StepState,
    /** What was found. */
    val status: String,
    /** What the step makes possible. */
    val about: String,
    /** What running it changes, and where. */
    val changes: String,
    /** The commands a click runs, one after another. Empty when there is nothing to run. */
    val commands: List<SetupCommand>,
    /** The label of the button that runs them. */
    val action: String,
)

/** Where the system keeps the CLI's installer and package manager. */
class SetupHost(val os: Os, val brew: String?, val homeDir: String)

private fun failureText(result: CallResult<*>?): String? = (result as? CallResult.Failed)?.failure?.message

private fun shortPath(path: String, homeDir: String): String = if (homeDir.isNotEmpty() && path.startsWith(homeDir)) "~" + path.removePrefix(homeDir) else path

/**
 * The steps for these facts and choices. A step that needs another one says
 * so and offers nothing to run. Nothing here runs a command.
 */
fun setupSteps(facts: SetupFacts, options: SetupOptions, host: SetupHost): List<SetupStep> {
    val cli = facts.cliPath.takeIf { facts.cliState == CliState.FOUND }
    val repo = facts.repoName ?: "this repository"
    val steps = ArrayList<SetupStep>()

    // 1. The CLI. An update goes the way the CLI was installed: Homebrew only for a CLI that Homebrew put there.
    val update = facts.cliState == CliState.TOO_OLD
    // Homebrew runs on macOS and Linux, so its paths use '/' on whatever machine reads them: /opt/homebrew/bin/brew has the prefix /opt/homebrew.
    val brewPrefix = host.brew?.substringBeforeLast('/', "")?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
    val viaHomebrew = host.brew != null && (!update || (brewPrefix != null && facts.cliPath?.startsWith("$brewPrefix/") == true))
    val installer = installCli(host.os, if (viaHomebrew) host.brew else null, update)
    val viaBrew = installer.program == host.brew
    steps.add(
        SetupStep(
            id = StepId.CLI,
            title = "Entire CLI",
            state = if (facts.cliState == CliState.FOUND) StepState.DONE else StepState.TODO,
            status = when (facts.cliState) {
                CliState.FOUND -> "Entire CLI ${facts.cliVersion.orEmpty()} at ${shortPath(facts.cliPath.orEmpty(), host.homeDir)}."
                CliState.TOO_OLD -> "Entire CLI ${facts.cliVersion.orEmpty()} is older than the $MIN_CLI_VERSION_TEXT this plugin needs."
                CliState.MISSING ->
                    if (facts.configuredPath != null) {
                        "No file that can be run is at ${facts.configuredPath}, the path set under Settings > Tools > Entire."
                    } else {
                        "The Entire CLI is not on the PATH or in its usual install locations."
                    }
            },
            about = "The plugin reads checkpoints, sessions, and trails through it.",
            changes = if (viaBrew) {
                "Installs the Homebrew cask entireio/tap/entire."
            } else {
                "Downloads Entire's installer from entire.com and runs it. It puts entire and git-remote-entire in ~/.local/bin."
            },
            commands = if (facts.cliState == CliState.FOUND) emptyList() else listOf(installer),
            action = if (facts.cliState == CliState.TOO_OLD) "Update" else "Install",
        ),
    )

    // 2. The login. It is for the machine, and not for one repository.
    val login = facts.login
    val loggedIn = (login as? CallResult.Ok)?.value?.loggedIn == true
    steps.add(
        SetupStep(
            id = StepId.LOGIN,
            title = "Log In",
            state = when {
                cli == null -> StepState.BLOCKED
                login == null -> StepState.CHECKING
                loggedIn -> StepState.DONE
                else -> StepState.TODO
            },
            status = when {
                cli == null -> "Needs the Entire CLI."
                login == null -> "Checking…"
                loggedIn -> (login as CallResult.Ok).value.user?.let { "Logged in as $it." } ?: "Logged in."
                login is CallResult.Failed -> "The plugin could not read the login: ${failureText(login)}"
                else -> "Not logged in."
            },
            about = "Search, trails, session names from entire.com, and the hosted brain use the login.",
            changes = if (options.deviceLogin) {
                "Prints a code to enter at entire.com, and saves the login in the system keychain."
            } else {
                "Opens the browser to log in, and saves the login in the system keychain."
            },
            commands = if (cli == null || login == null || loggedIn) emptyList() else listOf(login(cli, options.deviceLogin)),
            action = "Log In",
        ),
    )

    // 3. Entire in the repository.
    val installedAgents = (facts.agents as? CallResult.Ok)?.value?.filter { it.installed }?.map { it.name }.orEmpty()
    steps.add(
        SetupStep(
            id = StepId.ENABLE,
            title = "Enable Entire",
            state = when {
                facts.root == null -> StepState.NOT_APPLICABLE
                cli == null -> StepState.BLOCKED
                facts.enabled == null -> StepState.CHECKING
                facts.enabled -> StepState.DONE
                else -> StepState.TODO
            },
            status = when {
                facts.root == null -> "Open a folder that contains a Git repository."
                cli == null -> "Needs the Entire CLI."
                facts.enabled == null -> "Checking…"
                facts.enabled ->
                    if (installedAgents.isEmpty()) "Entire is enabled in $repo." else "Entire is enabled in $repo, with hooks for ${installedAgents.joinToString(", ")}."
                options.agents.isEmpty() -> "Entire is not enabled in $repo. Choose at least one agent."
                else -> "Entire is not enabled in $repo."
            },
            about = "Entire records agent sessions as checkpoints on the commits they lead to. The Checkpoints and Sessions tabs need it.",
            changes = "Installs Entire's git hooks and each agent's hooks, and writes .entire/settings.json. Commit the files the CLI lists.",
            commands = if (cli == null || facts.enabled != false) emptyList() else enable(cli, options.agents.toList(), options.telemetry),
            action = "Enable",
        ),
    )

    // 4. The graph.
    val graph = facts.graph
    val graphInstalled = graph is CallResult.Ok
    val graphMissing = (graph as? CallResult.Failed)?.failure?.kind == FailureKind.PLUGIN_MISSING
    val graphDone = graphInstalled && facts.agentGuide
    steps.add(
        SetupStep(
            id = StepId.GRAPH,
            title = "Graph",
            state = when {
                facts.root == null -> StepState.NOT_APPLICABLE
                cli == null -> StepState.BLOCKED
                graph == null -> StepState.CHECKING
                graphDone -> StepState.DONE
                else -> StepState.TODO
            },
            status = when {
                facts.root == null -> "Open a folder that contains a Git repository."
                cli == null -> "Needs the Entire CLI."
                graph == null -> "Checking…"
                graphDone -> "entire-graph ${(graph as CallResult.Ok).value} is installed, and $repo has the agent guide."
                graphInstalled -> "entire-graph ${(graph as CallResult.Ok).value} is installed. $repo does not have the agent guide yet."
                graphMissing -> "The entire-graph plugin is not installed."
                else -> "The plugin could not read the graph plugin: ${failureText(graph)}"
            },
            about = "A code graph that agents query for definitions, callers, and the blast radius of a change. It sends nothing over the network.",
            changes = "Installs the entire-graph plugin when it is missing. Writes the agent guide, references to it in AGENTS.md and CLAUDE.md, " +
                "and a hook in .claude/settings.local.json. Builds the graph of the checked-out commit.",
            // A read that failed for another reason says why, and offers nothing to run: installing would not mend it.
            commands = if (cli == null || graph == null || graphDone || (graph is CallResult.Failed && !graphMissing)) {
                emptyList()
            } else {
                (if (graphInstalled) emptyList() else listOf(installPlugin(cli, "graph"))) + graphSetup(cli)
            },
            action = if (graphInstalled) "Set Up" else "Install and Set Up",
        ),
    )

    // 5. The brain.
    val brain = facts.brain
    val brainStatus = (brain as? CallResult.Ok)?.value
    val brainMissing = (brain as? CallResult.Failed)?.failure?.kind == FailureKind.PLUGIN_MISSING
    val built = brainStatus != null && hasBrain(brainStatus)
    val unwired = brainStatus?.warnings?.any { it.startsWith("agents not wired") } == true
    val brainDone = built && !unwired
    steps.add(
        SetupStep(
            id = StepId.BRAIN,
            title = "Brain",
            state = when {
                facts.root == null -> StepState.NOT_APPLICABLE
                cli == null -> StepState.BLOCKED
                brain == null -> StepState.CHECKING
                brainDone -> StepState.DONE
                else -> StepState.TODO
            },
            status = when {
                facts.root == null -> "Open a folder that contains a Git repository."
                cli == null -> "Needs the Entire CLI."
                brain == null -> "Checking…"
                brainDone -> "The brain of $repo is built."
                built -> "The brain of $repo is built, and the agents are not wired to it yet."
                brainStatus != null -> "$repo has no brain yet."
                brainMissing -> "The entire-brain plugin is not installed."
                else -> "The plugin could not read the brain: ${failureText(brain)}"
            },
            about = "The Brain tab, and context from past sessions and decisions for the agents.",
            changes = "Installs the entire-brain plugin when it is missing. Builds the brain under ~/.local/state/entire and writes the agent guide. " +
                (if (options.daemon) "Installs one background watcher for this machine (launchd or systemd). " else "") +
                (if (options.backfill) "Distills past sessions into memories, which spends agent tokens." else "Spends no agent tokens."),
            commands = if (cli == null || brain == null || brainDone || (brain is CallResult.Failed && !brainMissing)) {
                emptyList()
            } else {
                (if (brainMissing) listOf(installPlugin(cli, "brain")) else emptyList()) + brainSetup(cli, options.backfill, options.daemon)
            },
            action = if (brainMissing) "Install and Set Up" else "Set Up",
        ),
    )

    // 6. The hosted brain.
    val connected = brainStatus?.hosted == "connected"
    steps.add(
        SetupStep(
            id = StepId.HOSTED,
            title = "Hosted Brain",
            state = when {
                facts.root == null || !facts.hosted -> StepState.NOT_APPLICABLE
                cli == null -> StepState.BLOCKED
                login == null || brain == null -> StepState.CHECKING
                !loggedIn || !built -> StepState.BLOCKED
                connected -> StepState.DONE
                else -> StepState.TODO
            },
            status = when {
                facts.root == null -> "Open a folder that contains a Git repository."
                !facts.hosted -> "The origin of $repo is not a repository on Entire."
                cli == null -> "Needs the Entire CLI."
                login == null || brain == null -> "Checking…"
                !loggedIn -> "Needs the login."
                !built -> "Needs the brain."
                connected -> if (brainStatus.hostedSynced == false) "Connected, and not synced yet." else "Connected and synced."
                else -> "Not connected."
            },
            about = "Shares durable memories with the team through Entire.",
            changes = "Publishes the memories of $repo to its hosted brain on Entire, and pulls the team's.",
            commands = if (cli == null || !facts.hosted || !loggedIn || !built || connected) emptyList() else listOf(brainConnect(cli)),
            action = "Connect",
        ),
    )
    return steps
}
