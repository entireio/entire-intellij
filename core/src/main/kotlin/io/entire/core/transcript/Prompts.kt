package io.entire.core.transcript

import io.entire.core.json.jsTrim

/**
 * The leading markers of content an agent puts on the user side of a
 * transcript: runtime preambles, instruction-file dumps, and tool or
 * notification results replayed as user turns. None of it is typed by the
 * user.
 *
 * The Entire CLI keeps this list (`transcript/injected_prompt.go`) and uses
 * it to pick a session's title, so the same list is used here. It is matched
 * at the start of a prompt only: a prompt that mentions one of these tags is
 * still a prompt.
 */
private val INJECTED_PREFIXES = listOf(
    "# AGENTS.md",
    "<environment_context>",
    "<permissions",
    "<collaboration_mode>",
    "<skills_instructions>",
    "<apps_instructions>",
    "<plugins_instructions>",
    "<recommended_plugins>",
    "<user_action>",
    "<subagent_notification>",
    "<turn_aborted>",
)

/** Whether a prompt was put there by the agent and not typed by the user. */
fun isInjectedPrompt(text: String): Boolean {
    val trimmed = text.jsTrim()
    if (INJECTED_PREFIXES.any { trimmed.startsWith(it) }) return true
    // Some instruction dumps lead with a wrapper tag. The marker keeps a real question about the tag a prompt.
    return trimmed.startsWith("<INSTRUCTIONS>") && trimmed.contains("AGENTS.md instructions")
}

/** What a turn says in words: its text blocks, one after another. */
private fun turnText(turn: FullTurn): String =
    turn.blocks
        .map { if (it is FullBlock.Text) it.text else "" }
        .filter { it.jsTrim().isNotEmpty() }
        .joinToString("\n")
        .jsTrim()

/**
 * The prompt that opens a session, which is what Entire names the session
 * by: the first thing the user typed. A prompt the agent injected does not
 * count. When every prompt is one of those, the first of them is returned,
 * because a noisy name says more than none.
 */
fun openingPrompt(turns: List<FullTurn>): String? {
    var injected: String? = null
    for (turn in turns) {
        if (turn.role != Role.USER) continue
        val text = turnText(turn)
        if (text.isEmpty()) continue
        if (!isInjectedPrompt(text)) return text
        if (injected == null) injected = text
    }
    return injected
}
