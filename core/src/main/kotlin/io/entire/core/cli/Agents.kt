package io.entire.core.cli

/** An agent that Entire can install hooks for, and whether this repository has them. */
data class AgentChoice(val name: String, val installed: Boolean)

/**
 * The agents the CLI knows, in the order its documentation lists them. The
 * Setup tab offers these when `entire agent list` cannot be read.
 */
val KNOWN_AGENTS: List<String> = listOf("antigravity", "claude-code", "codex", "copilot-cli", "cursor", "factoryai-droid", "opencode", "pi")

/** An agent's name as the CLI spells it. It cannot start with a dash, so it is never read as a flag. */
private val AGENT_NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")

/**
 * Reads `entire agent list`, which prints one agent to a line under an
 * "Agents:" heading, with a check mark before the ones whose hooks this
 * repository has:
 *
 * ```
 * Agents:
 *     antigravity
 *   ✓ claude-code
 * ```
 *
 * Null when no line reads as an agent.
 */
fun readAgentList(text: String): List<AgentChoice>? {
    val agents = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.endsWith(":") }
        .mapNotNull { line ->
            val installed = line.startsWith("✓")
            val name = line.removePrefix("✓").trim().substringBefore(' ')
            if (AGENT_NAME.matches(name)) AgentChoice(name, installed) else null
        }
        .toList()
    return agents.ifEmpty { null }
}

/** The command each agent is started with, so the Setup tab can check the agents that are installed on this machine. */
val AGENT_COMMANDS: Map<String, String> = mapOf("claude-code" to "claude", "codex" to "codex")
