package io.entire.core.transcript

import io.entire.core.format.truncate
import io.entire.core.json.jsEntries
import io.entire.core.json.jsTrim
import io.entire.core.json.stringOrNull
import io.entire.core.json.stringify
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** A transcript block with everything the source recorded. Kept on the host. */
sealed interface FullBlock {
    data class Text(val text: String) : FullBlock

    /** `input` is what the agent passed to the tool, as it was recorded. Null when the record has none. */
    data class Tool(val name: String, val input: JsonElement?, val output: String? = null, val status: String? = null) : FullBlock

    data class Image(val mediaType: String? = null, val base64: String? = null, val assetPath: String? = null) : FullBlock

    data class Note(val text: String) : FullBlock
}

enum class Role(val wire: String) { USER("user"), ASSISTANT("assistant") }

class FullTurn(val role: Role, val ts: String?, val blocks: MutableList<FullBlock>)

class ParsedTranscript(
    val turns: List<FullTurn>,
    /** Index of the first turn that belongs to the checkpoint being viewed. */
    val startTurn: Int,
)

private const val SUMMARY_LIMIT = 160

/** Input keys that usually say what a tool call did, most descriptive first. */
private val SUMMARY_KEYS = listOf("description", "command", "file_path", "path", "pattern", "query", "url", "prompt", "skill", "subject")

private fun oneLine(text: String): String {
    val line = text.split('\n').firstOrNull { it.jsTrim().isNotEmpty() }.orEmpty()
    return truncate(line.jsTrim(), SUMMARY_LIMIT)
}

/** One line that tells a reader what a tool call did, without the full input. */
fun toolSummary(input: JsonElement?): String {
    input.stringOrNull()?.let { return oneLine(it) }
    val record = input as? JsonObject ?: return ""
    for (key in SUMMARY_KEYS) {
        val value = record[key].stringOrNull()
        if (value != null && value.jsTrim().isNotEmpty()) return oneLine(value)
    }
    for ((_, entry) in record.jsEntries()) {
        val value = entry.stringOrNull()
        if (value != null && value.jsTrim().isNotEmpty()) return oneLine(value)
    }
    return ""
}

/** The tool input as text for display. */
fun toolInputText(input: JsonElement?): String {
    if (input == null || input is JsonNull) return ""
    input.stringOrNull()?.let { return it }
    return stringify(input, "  ")
}

/** A block as the panel's page gets it: without tool bodies and image data. */
sealed interface WireBlock {
    data class Text(val text: String) : WireBlock

    data class Tool(val block: Int, val name: String, val summary: String, val status: String?, val hasBody: Boolean) : WireBlock

    data class Image(val block: Int, val mediaType: String?) : WireBlock

    data class Note(val text: String) : WireBlock
}

data class WireTurn(val index: Int, val role: Role, val ts: String?, val blocks: List<WireBlock>)

/** Strips tool bodies and image data, which make up most of a transcript's size. */
fun toWireTurn(turn: FullTurn, index: Int): WireTurn {
    val blocks = turn.blocks.mapIndexed { position, block ->
        when (block) {
            is FullBlock.Text -> WireBlock.Text(block.text)
            is FullBlock.Note -> WireBlock.Note(block.text)
            is FullBlock.Image -> WireBlock.Image(position, block.mediaType?.ifEmpty { null })
            is FullBlock.Tool -> WireBlock.Tool(
                block = position,
                name = block.name,
                summary = toolSummary(block.input),
                status = block.status?.ifEmpty { null },
                hasBody = toolInputText(block.input).isNotEmpty() || !block.output.isNullOrEmpty(),
            )
        }
    }
    return WireTurn(index, turn.role, turn.ts?.ifEmpty { null }, blocks)
}
