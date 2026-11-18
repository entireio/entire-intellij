package io.entire.core.setup

/*
 * The commands that the Setup tab runs, one per click. Each of them writes:
 * it installs the Entire CLI or a plugin, logs in, enables Entire in the
 * repository, builds the graph or the brain, or connects the repository to
 * its hosted brain.
 *
 * They are built here and nowhere else, and [isSetupCommand] accepts exactly
 * these shapes. The read path, `EntireCli`, refuses all of them.
 */

/** The machine the IDE runs on, as far as installing the CLI goes. */
enum class Os { MAC, LINUX, WINDOWS }

/**
 * One command: a program and its arguments, run without a shell unless the
 * program is one. `display` is how the Setup tab shows it before the click,
 * with `entire` in place of the CLI's full path.
 */
data class SetupCommand(val program: String, val args: List<String>, val display: String)

/** The plugins the Setup tab installs. */
val SETUP_PLUGINS: Set<String> = setOf("brain", "graph")

private val AGENT_NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")

private const val INSTALL_SH = "curl -fsSL https://entire.com/install.sh | bash"
private const val INSTALL_PS1 = "irm https://entire.com/install.ps1 | iex"
private const val BREW_CASK = "entireio/tap/entire"

/**
 * Installs the Entire CLI, or updates it when `update` is set, the way its
 * documentation says for each system. On macOS that is Homebrew when the
 * IDE finds it, and the install script otherwise. Running the install script
 * again updates the CLI.
 */
fun installCli(os: Os, brew: String?, update: Boolean): SetupCommand =
    when {
        os == Os.WINDOWS -> SetupCommand("powershell.exe", listOf("-NoProfile", "-Command", INSTALL_PS1), INSTALL_PS1)
        os == Os.MAC && brew != null -> {
            val verb = if (update) "upgrade" else "install"
            SetupCommand(brew, listOf(verb, "--cask", BREW_CASK), "brew $verb --cask $BREW_CASK")
        }
        else -> SetupCommand("/bin/bash", listOf("-c", INSTALL_SH), INSTALL_SH)
    }

private fun entire(cli: String, vararg args: String): SetupCommand = SetupCommand(cli, args.toList(), "entire " + args.joinToString(" "))

/** Logs in to Entire. The CLI opens the browser and waits. With `device`, it prints a code to enter in the browser instead. */
fun login(cli: String, device: Boolean = false): SetupCommand = if (device) entire(cli, "login", "--device") else entire(cli, "login")

/**
 * Enables Entire in the repository with hooks for the first agent, and adds
 * each other agent after it. Naming an agent makes `entire enable` run
 * without asking anything.
 */
fun enable(cli: String, agents: List<String>, telemetry: Boolean): List<SetupCommand> {
    val chosen = agents.filter { AGENT_NAME.matches(it) }.distinct()
    val first = chosen.firstOrNull() ?: return emptyList()
    return listOf(entire(cli, "enable", "--agent", first, "--telemetry=$telemetry")) + chosen.drop(1).map { entire(cli, "agent", "add", it) }
}

fun installPlugin(cli: String, plugin: String): SetupCommand {
    require(plugin in SETUP_PLUGINS) { "The Setup tab does not install the plugin $plugin." }
    return entire(cli, "plugin", "install", plugin)
}

/** Writes the agent guide and its references in AGENTS.md and CLAUDE.md, then builds the graph of the checked-out commit. */
fun graphSetup(cli: String): List<SetupCommand> =
    listOf(entire(cli, "graph", "setup", "--repo", "."), entire(cli, "graph", "index", "--repo", ".", "--head", "--format", "text"))

/**
 * Builds the brain. Distilling past sessions spends agent tokens, so it runs
 * only with `backfill`, which also answers the CLI's question about it. The
 * background watcher is installed only with `daemon`.
 */
fun brainSetup(cli: String, backfill: Boolean, daemon: Boolean): SetupCommand {
    val args = mutableListOf("brain", "setup")
    if (backfill) args.addAll(listOf("--backfill", "--yes")) else args.add("--no-backfill")
    args.add(if (daemon) "--daemon" else "--no-daemon")
    return entire(cli, *args.toTypedArray())
}

/** Starts syncing the repository's memories with its hosted brain on Entire. */
fun brainConnect(cli: String): SetupCommand = entire(cli, "brain", "connect")

/** Whether arguments of the Entire CLI are one of the setup commands above. */
fun isSetupArgs(args: List<String>): Boolean {
    val first = args.getOrNull(0)
    val second = args.getOrNull(1)
    return when (first) {
        "login" -> args == listOf("login") || args == listOf("login", "--device")
        "enable" -> args.size == 4 && args[1] == "--agent" && AGENT_NAME.matches(args[2]) && (args[3] == "--telemetry=true" || args[3] == "--telemetry=false")
        "agent" -> args.size == 3 && second == "add" && AGENT_NAME.matches(args[2])
        "plugin" -> args.size == 3 && second == "install" && args[2] in SETUP_PLUGINS
        "graph" -> args == listOf("graph", "setup", "--repo", ".") || args == listOf("graph", "index", "--repo", ".", "--head", "--format", "text")
        "brain" -> args == listOf("brain", "connect") ||
            listOf(true, false).any { backfill -> listOf(true, false).any { daemon -> brainSetup("", backfill, daemon).args == args } }
        else -> false
    }
}

/**
 * Whether a command is one the Setup tab may run: the Entire CLI at `cli`
 * with arguments [isSetupArgs] accepts, or one of the documented installers.
 */
fun isSetupCommand(command: SetupCommand, cli: String?, brew: String?): Boolean {
    if (cli != null && command.program == cli) return isSetupArgs(command.args)
    val installers = Os.entries.flatMap { os -> listOf(installCli(os, brew, update = false), installCli(os, brew, update = true)) }
    return installers.any { it.program == command.program && it.args == command.args }
}
