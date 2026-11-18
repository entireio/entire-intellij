package io.entire.core.setup

import io.entire.core.cli.isReadCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SetupCommandsTest {
    private val cli = "/usr/local/bin/entire"
    private val brew = "/opt/homebrew/bin/brew"

    private fun everyCommand(): List<SetupCommand> =
        listOf(login(cli), login(cli, device = true), brainConnect(cli), installPlugin(cli, "graph"), installPlugin(cli, "brain")) +
            enable(cli, listOf("claude-code", "codex"), telemetry = true) +
            enable(cli, listOf("cursor"), telemetry = false) +
            graphSetup(cli) +
            listOf(true, false).flatMap { backfill -> listOf(true, false).map { daemon -> brainSetup(cli, backfill, daemon) } }

    @Test
    fun `every command the Setup tab builds is one it may run`() {
        for (command in everyCommand()) assertTrue(isSetupCommand(command, cli, brew), command.display)
    }

    @Test
    fun `the read path refuses every setup command`() {
        for (command in everyCommand()) assertFalse(isReadCommand(command.args), command.display)
    }

    @Test
    fun `a command for a program other than the CLI is refused`() {
        assertFalse(isSetupCommand(login("/tmp/entire"), cli, brew))
        assertFalse(isSetupCommand(SetupCommand("/bin/sh", listOf("-c", "rm -rf ~"), "rm -rf ~"), cli, brew))
        // Without a CLI, only the installers pass.
        assertFalse(isSetupCommand(login(cli), null, brew))
    }

    @Test
    fun `arguments outside the built shapes are refused`() {
        listOf(
            listOf("login", "--token", "x"),
            listOf("enable"),
            listOf("enable", "--agent", "--force", "--telemetry=true"),
            listOf("enable", "--agent", "claude-code", "--telemetry=maybe"),
            listOf("agent", "add", "-x"),
            listOf("agent", "remove", "codex"),
            listOf("plugin", "install", "other"),
            listOf("plugin", "remove", "brain"),
            listOf("graph", "setup", "--repo", "/"),
            listOf("brain", "connect", "--all"),
            listOf("brain", "setup", "--backfill", "--daemon"),
            listOf("disable"),
            listOf("clean", "--force"),
            emptyList(),
        ).forEach { assertFalse(isSetupArgs(it), it.joinToString(" ")) }
    }

    @Test
    fun `enable names the first agent and adds the others, without flags or repeats`() {
        val commands = enable(cli, listOf("-rf", "codex", "codex", "cursor"), telemetry = false)
        assertEquals(
            listOf(listOf("enable", "--agent", "codex", "--telemetry=false"), listOf("agent", "add", "cursor")),
            commands.map { it.args },
        )
        assertEquals(emptyList<SetupCommand>(), enable(cli, emptyList(), telemetry = true))
    }

    @Test
    fun `brain setup spends tokens and installs the watcher only when asked`() {
        assertEquals(listOf("brain", "setup", "--no-backfill", "--no-daemon"), brainSetup(cli, backfill = false, daemon = false).args)
        assertEquals(listOf("brain", "setup", "--backfill", "--yes", "--daemon"), brainSetup(cli, backfill = true, daemon = true).args)
    }

    @Test
    fun `only the brain and graph plugins install`() {
        assertTrue(runCatching { installPlugin(cli, "other") }.isFailure)
    }

    @Test
    fun `the installer follows the system and Homebrew`() {
        val cask = "entireio/tap/entire"
        assertEquals(SetupCommand(brew, listOf("install", "--cask", cask), "brew install --cask $cask"), installCli(Os.MAC, brew, update = false))
        assertEquals(SetupCommand(brew, listOf("upgrade", "--cask", cask), "brew upgrade --cask $cask"), installCli(Os.MAC, brew, update = true))
        val script = "curl -fsSL https://entire.com/install.sh | bash"
        assertEquals(SetupCommand("/bin/bash", listOf("-c", script), script), installCli(Os.MAC, null, update = false))
        assertEquals(SetupCommand("/bin/bash", listOf("-c", script), script), installCli(Os.LINUX, brew, update = true))
        assertEquals("powershell.exe", installCli(Os.WINDOWS, brew, update = false).program)
    }

    @Test
    fun `only the documented installers pass`() {
        for (os in Os.entries) for (update in listOf(true, false)) assertTrue(isSetupCommand(installCli(os, brew, update), cli, brew))
        assertTrue(isSetupCommand(installCli(Os.MAC, null, update = false), null, null))
        assertFalse(isSetupCommand(SetupCommand("/bin/bash", listOf("-c", "curl -fsSL https://example.com/install.sh | bash"), ""), cli, brew))
        assertFalse(isSetupCommand(SetupCommand(brew, listOf("install", "--cask", "other/tap/entire"), ""), cli, brew))
    }
}
