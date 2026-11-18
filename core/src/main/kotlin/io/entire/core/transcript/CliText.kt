package io.entire.core.transcript

import io.entire.core.json.jsTrim
import kotlinx.serialization.json.JsonPrimitive

/*
 * Reader for the transcript section of `entire checkpoint explain --full`.
 *
 * This is the fallback for sessions with no normalized transcript. The CLI
 * prints a header, then a `Transcript` rule, then entries that each start
 * with `[User]`, `[Assistant]`, or `[Tool]`. The text carries tool names and
 * one-line descriptions but no tool inputs or results.
 *
 * It is a text format meant for people, so parsing is best effort: when no
 * entry marker is found, the whole section becomes one text turn.
 */

private val MARKER = Regex("\\[(User|Assistant|Tool)\\] ?(.*)")
private val TRANSCRIPT_RULE = Regex("─+ Transcript\\b.*─+\\s*")

/** The plain rule that ends the header of the CLI's text views. */
private val HEADER_RULE = Regex("─{3,}\\s*")
private val AUTHOR_LINE = Regex("\\s*author\\s+(\\S.*)")
private val WITH_ADDRESS = Regex("(.*?)\\s*<([^<>]*)>")
private val LINE_BREAK = Regex("\r\n?")

/** The header is a handful of lines. Past this, a line that reads like one belongs to the text below. */
private const val HEADER_LINES = 40

/** A name is drawn beside each prompt, so one that runs on is cut. */
private const val AUTHOR_MAX = 80

/**
 * Who the CLI names as the author of a checkpoint, from the header of
 * `entire checkpoint explain --short`. The line reads
 * `  author   Ada Lovelace <ada@example.com>`.
 *
 * The CLI takes the author from the commit that carries the checkpoint, and
 * failing that from the commit that stored the checkpoint's data. Its JSON
 * output leaves the author out, which is why the text view is read.
 *
 * Returns the name, or the address when the line has no name.
 */
fun parseCheckpointAuthor(output: String): String? {
    val lines = output.replace(LINE_BREAK, "\n").split('\n').take(HEADER_LINES)
    for (line in lines) {
        if (HEADER_RULE.matches(line)) return null
        val match = AUTHOR_LINE.matchEntire(line) ?: continue
        val value = match.groupValues[1].jsTrim()
        val withAddress = WITH_ADDRESS.matchEntire(value)
        val name = (withAddress?.groupValues?.get(1) ?: value).jsTrim()
        val author = name.ifEmpty { withAddress?.groupValues?.get(2).orEmpty().jsTrim() }
        return if (author.isEmpty()) null else author.take(AUTHOR_MAX)
    }
    return null
}

fun parseCliText(output: String): ParsedTranscript {
    val lines = output.replace(LINE_BREAK, "\n").split('\n')
    val ruleIndex = lines.indexOfFirst { TRANSCRIPT_RULE.matches(it) }
    val body = if (ruleIndex >= 0) lines.drop(ruleIndex + 1) else lines

    val turns = ArrayList<FullTurn>()
    var role: Role? = null
    var text = ArrayList<String>()

    fun flushText() {
        val joined = text.joinToString("\n").jsTrim()
        text = ArrayList()
        val current = role
        if (joined.isEmpty() || current == null) return
        val last = turns.lastOrNull()
        if (current == Role.ASSISTANT && last?.role == Role.ASSISTANT) {
            last.blocks.add(FullBlock.Text(joined))
        } else {
            turns.add(FullTurn(current, null, mutableListOf(FullBlock.Text(joined))))
        }
    }

    for (line in body) {
        val match = MARKER.matchEntire(line)
        if (match == null) {
            text.add(line)
            continue
        }
        flushText()
        val kind = match.groupValues[1]
        val rest = match.groupValues[2]
        if (kind == "Tool") {
            role = Role.ASSISTANT
            val separator = rest.indexOf(": ")
            val name = if (separator > 0) rest.substring(0, separator) else rest.ifEmpty { "Tool" }
            val description = if (separator > 0) rest.substring(separator + 2) else ""
            val block = FullBlock.Tool(name = name, input = JsonPrimitive(description))
            val last = turns.lastOrNull()
            if (last?.role == Role.ASSISTANT) last.blocks.add(block) else turns.add(FullTurn(Role.ASSISTANT, null, mutableListOf(block)))
        } else {
            role = if (kind == "User") Role.USER else Role.ASSISTANT
            text.add(rest)
        }
    }
    flushText()

    if (turns.isEmpty()) {
        val whole = body.joinToString("\n").jsTrim()
        if (whole.isNotEmpty()) turns.add(FullTurn(Role.ASSISTANT, null, mutableListOf(FullBlock.Text(whole))))
    }
    return ParsedTranscript(turns, 0)
}
