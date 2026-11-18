package io.entire.app.setup

import io.entire.core.CallResult
import io.entire.core.FailureKind
import io.entire.core.brain.BrainStatus
import io.entire.core.cli.AgentChoice
import io.entire.core.cli.AuthStatus
import io.entire.core.fail
import io.entire.core.ok
import io.entire.core.setup.Os
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SetupStepsTest {
    private val brew = "/opt/homebrew/bin/brew"
    private val mac = SetupHost(Os.MAC, brew, "/Users/ada")
    private val cli = "/Users/ada/.local/bin/entire"

    /** A machine with the CLI and a login, and a hosted repository where Entire is enabled and nothing else is set up. */
    private val facts = SetupFacts(
        cliState = CliState.FOUND,
        cliPath = cli,
        cliVersion = "0.13.4",
        root = "/Users/ada/forgemark",
        repoName = "forgemark",
        hosted = true,
        login = ok(AuthStatus(loggedIn = true, user = "ada")),
        enabled = true,
        agents = ok(listOf(AgentChoice("claude-code", true))),
        graph = fail(FailureKind.PLUGIN_MISSING, "The entire-graph plugin is not installed."),
        brain = ok(BrainStatus(manifestState = "absent")),
    )

    private fun steps(facts: SetupFacts, options: SetupOptions = SetupOptions(), host: SetupHost = mac) = setupSteps(facts, options, host).associateBy { it.id }

    private fun states(facts: SetupFacts) = setupSteps(facts, SetupOptions(), mac).map { it.state }

    private fun SetupStep.shown() = commands.map { it.display }

    @Test
    fun `reads each step from the facts`() {
        assertEquals(
            listOf(StepState.DONE, StepState.DONE, StepState.DONE, StepState.TODO, StepState.TODO, StepState.BLOCKED),
            states(facts),
        )
    }

    @Test
    fun `without the CLI, every other step waits for it`() {
        val missing = SetupFacts(cliState = CliState.MISSING, root = "/r", hosted = true)
        assertEquals(listOf(StepState.TODO) + List(5) { StepState.BLOCKED }, states(missing))
        assertEquals(listOf("brew install --cask entireio/tap/entire"), steps(missing).getValue(StepId.CLI).shown())
    }

    @Test
    fun `an old CLI updates the way it was installed`() {
        val script = steps(facts.copy(cliState = CliState.TOO_OLD)).getValue(StepId.CLI)
        assertEquals(listOf("curl -fsSL https://entire.com/install.sh | bash"), script.shown())
        assertEquals("Update", script.action)
        val cask = steps(facts.copy(cliState = CliState.TOO_OLD, cliPath = "/opt/homebrew/bin/entire")).getValue(StepId.CLI)
        assertEquals(listOf("brew upgrade --cask entireio/tap/entire"), cask.shown())
    }

    @Test
    fun `enable runs with the chosen agents and telemetry`() {
        val enable = steps(facts.copy(enabled = false), SetupOptions(agents = linkedSetOf("claude-code", "codex"), telemetry = false)).getValue(StepId.ENABLE)
        assertEquals(StepState.TODO, enable.state)
        assertEquals(listOf("entire enable --agent claude-code --telemetry=false", "entire agent add codex"), enable.shown())
        // With no agent chosen, there is nothing to run yet.
        assertEquals(emptyList<String>(), steps(facts.copy(enabled = false)).getValue(StepId.ENABLE).shown())
    }

    @Test
    fun `a missing graph plugin is installed before the graph is set up`() {
        val graph = steps(facts).getValue(StepId.GRAPH)
        assertEquals(
            listOf("entire plugin install graph", "entire graph setup --repo .", "entire graph index --repo . --head --format text"),
            graph.shown(),
        )
        assertEquals("Install and Set Up", graph.action)
        val installed = steps(facts.copy(graph = ok("v0.6.0"))).getValue(StepId.GRAPH)
        assertEquals(listOf("entire graph setup --repo .", "entire graph index --repo . --head --format text"), installed.shown())
        assertEquals(StepState.DONE, steps(facts.copy(graph = ok("v0.6.0"), agentGuide = true)).getValue(StepId.GRAPH).state)
    }

    @Test
    fun `a read that failed for another reason offers nothing to run`() {
        val broken = facts.copy(graph = fail(FailureKind.EXIT, "boom"), brain = fail(FailureKind.EXIT, "boom"))
        assertEquals(emptyList<String>(), steps(broken).getValue(StepId.GRAPH).shown())
        assertEquals(emptyList<String>(), steps(broken).getValue(StepId.BRAIN).shown())
    }

    @Test
    fun `the brain spends no tokens and installs no watcher unless asked`() {
        val brain = steps(facts, SetupOptions(daemon = false)).getValue(StepId.BRAIN)
        assertEquals(listOf("entire brain setup --no-backfill --no-daemon"), brain.shown())
        assertEquals("Set Up", brain.action)
        val missing = steps(facts.copy(brain = fail(FailureKind.PLUGIN_MISSING, "missing")), SetupOptions(backfill = true)).getValue(StepId.BRAIN)
        assertEquals(listOf("entire plugin install brain", "entire brain setup --backfill --yes --daemon"), missing.shown())
    }

    @Test
    fun `a built brain whose agents are not wired is still to do`() {
        val unwired = facts.copy(brain = ok(BrainStatus(manifestState = "current", warnings = listOf("agents not wired: run entire brain setup"))))
        assertEquals(StepState.TODO, steps(unwired).getValue(StepId.BRAIN).state)
        assertEquals(StepState.DONE, steps(facts.copy(brain = ok(BrainStatus(manifestState = "current")))).getValue(StepId.BRAIN).state)
    }

    @Test
    fun `the hosted brain applies only to a repository on Entire`() {
        val hosted = steps(facts.copy(hosted = false)).getValue(StepId.HOSTED)
        assertEquals(StepState.NOT_APPLICABLE, hosted.state)
        assertEquals(emptyList<String>(), hosted.shown())
    }

    @Test
    fun `the hosted brain waits for the login and the brain, then connects`() {
        val built = facts.copy(brain = ok(BrainStatus(manifestState = "current", hosted = "disconnected")))
        val loggedOut = steps(built.copy(login = ok(AuthStatus(loggedIn = false, user = null)))).getValue(StepId.HOSTED)
        assertEquals(listOf(StepState.BLOCKED.name, "Needs the login."), listOf(loggedOut.state.name, loggedOut.status))
        assertEquals("Needs the brain.", steps(facts).getValue(StepId.HOSTED).status)
        val connect = steps(built).getValue(StepId.HOSTED)
        assertEquals(StepState.TODO, connect.state)
        assertEquals(listOf("entire brain connect"), connect.shown())
        assertEquals(StepState.DONE, steps(facts.copy(brain = ok(BrainStatus(manifestState = "current", hosted = "connected")))).getValue(StepId.HOSTED).state)
    }

    @Test
    fun `steps whose facts are being read say so`() {
        val reading = facts.copy(login = null, enabled = null, graph = null, brain = null)
        assertEquals(
            listOf(StepState.DONE, StepState.CHECKING, StepState.CHECKING, StepState.CHECKING, StepState.CHECKING, StepState.CHECKING),
            states(reading),
        )
        assertEquals(emptyList<String>(), setupSteps(reading, SetupOptions(), mac).flatMap { it.shown() })
    }

    @Test
    fun `a done step has nothing to run`() {
        val done = facts.copy(graph = ok("v0.6.0"), agentGuide = true, brain = ok(BrainStatus(manifestState = "current", hosted = "connected")))
        assertEquals(List(6) { StepState.DONE }, states(done))
        assertEquals(emptyList<String>(), setupSteps(done, SetupOptions(agents = setOf("codex")), mac).flatMap { it.shown() })
    }
}
