package io.entire.core.transcript

import io.entire.core.json.jsTrim
import io.entire.core.json.parseJson
import io.entire.core.json.stringOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Reader for the Entire Transcript Format v1 (`transcript.jsonl`).
 *
 * The format is one JSON object per line with `type` of `user` or `assistant`
 * and a `content` list of blocks. It is the same for every agent. Following
 * its evolution rules, this reader ignores unknown fields, skips lines with an
 * unknown `type`, and keeps unknown block types as a short note.
 */

private const val ASSET_PREFIX = "entire-asset:"
private val IMAGE_TYPE = Regex("image/[\\w.+-]+")
private val DATA_URL = Regex("data:(image/[\\w.+-]+);base64,(.*)", RegexOption.DOT_MATCHES_ALL)

private fun imageFromSource(data: String?, mediaType: String?): FullBlock.Image {
    val type = mediaType?.takeIf { IMAGE_TYPE.matches(it) }
    return when {
        data == null || data.isEmpty() -> FullBlock.Image(mediaType = type)
        data.startsWith(ASSET_PREFIX) -> FullBlock.Image(mediaType = type, assetPath = data.substring(ASSET_PREFIX.length))
        else -> FullBlock.Image(mediaType = type, base64 = data)
    }
}

/** Splits a `data:image/png;base64,...` URL, or recognizes an asset placeholder. */
private fun imageFromUrl(url: String?): FullBlock.Image? {
    if (url == null) return null
    if (url.startsWith(ASSET_PREFIX)) return FullBlock.Image(assetPath = url.substring(ASSET_PREFIX.length))
    val match = DATA_URL.matchEntire(url) ?: return null
    return imageFromSource(match.groupValues[2], match.groupValues[1])
}

private fun readBlock(raw: JsonElement): FullBlock? {
    raw.stringOrNull()?.let { text -> return if (text.jsTrim().isEmpty()) null else FullBlock.Text(text) }
    val block = raw as? JsonObject ?: return null
    val typeElement = block["type"]
    val type = typeElement.stringOrNull()

    // User text blocks have no `type`, which is a quirk of v1.
    if (typeElement == null || type == "text") {
        val text = block["text"].stringOrNull()
        return if (text != null && text.jsTrim().isNotEmpty()) FullBlock.Text(text) else null
    }

    if (type == "tool_use") {
        val result = block["result"] as? JsonObject
        return FullBlock.Tool(
            name = block["name"].stringOrNull()?.ifEmpty { null } ?: "Tool",
            input = block["input"],
            output = result?.get("output").stringOrNull(),
            status = result?.get("status").stringOrNull()?.takeIf { it == "success" || it == "error" },
        )
    }

    val source = block["source"] as? JsonObject
    if (type == "image" && source != null) {
        return imageFromSource(source["data"].stringOrNull(), source["media_type"].stringOrNull())
    }
    if (type == "input_image" || type == "image_url") {
        val imageUrl = block["image_url"]
        val url = if (imageUrl is JsonObject) imageUrl["url"] else imageUrl
        return imageFromUrl(url.stringOrNull()) ?: FullBlock.Note("Image")
    }

    return if (type != null) FullBlock.Note("Unsupported block: $type") else null
}

private fun readBlocks(content: JsonElement?): List<FullBlock> {
    content.stringOrNull()?.let { text -> return if (text.jsTrim().isEmpty()) emptyList() else listOf(FullBlock.Text(text)) }
    val items = content as? JsonArray ?: return emptyList()
    return items.mapNotNull { readBlock(it) }
}

/**
 * Parses a normalized transcript into turns.
 *
 * Consecutive assistant lines become one turn, because an agent writes one
 * line per message fragment while a reader thinks in replies. User lines with
 * no text (tool results that were folded into their tool call) are dropped.
 *
 * `startLine` is the session metadata's `compact_transcript_start`: a 0-based
 * line index. The line at that index can be the tail of the previous
 * checkpoint, so the checkpoint's own portion begins at the first user turn
 * at or after it.
 */
fun parseCompactTranscript(text: String, startLine: Int?): ParsedTranscript {
    val turns = ArrayList<FullTurn>()

    // Source lines where each turn begins and ends, parallel to `turns`.
    val firstLine = ArrayList<Int>()
    val lastLine = ArrayList<Int>()
    var previousWasAssistant = false

    val lines = text.split('\n')
    for (lineIndex in lines.indices) {
        val line = lines[lineIndex]
        if (line.jsTrim().isEmpty()) continue
        val parsed = parseJson(line) as? JsonObject ?: continue
        val role = when (parsed["type"].stringOrNull()) {
            "user" -> Role.USER
            "assistant" -> Role.ASSISTANT
            else -> continue
        }
        // `v` changes only for a breaking change, so a v1 reader cannot interpret another version.
        val version = (parsed["v"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        if (version != null && version != 1.0) continue

        val blocks = readBlocks(parsed["content"])
        if (blocks.isEmpty()) continue
        val ts = parsed["ts"].stringOrNull()?.ifEmpty { null }

        val last = turns.lastOrNull()
        if (role == Role.ASSISTANT && previousWasAssistant && last != null) {
            last.blocks.addAll(blocks)
            lastLine[lastLine.size - 1] = lineIndex
        } else {
            turns.add(FullTurn(role, ts, blocks.toMutableList()))
            firstLine.add(lineIndex)
            lastLine.add(lineIndex)
        }
        previousWasAssistant = role == Role.ASSISTANT
    }

    return ParsedTranscript(turns, findStartTurn(turns, firstLine, lastLine, startLine))
}

private fun findStartTurn(turns: List<FullTurn>, firstLine: List<Int>, lastLine: List<Int>, startLine: Int?): Int {
    if (startLine == null || startLine <= 0) return 0
    var firstAtOrAfter = -1
    for (i in turns.indices) {
        if (firstLine[i] < startLine) continue
        if (firstAtOrAfter < 0) firstAtOrAfter = i
        if (turns[i].role == Role.USER) return i
    }
    // No user turn after the offset: show from the first turn past it.
    if (firstAtOrAfter >= 0) return firstAtOrAfter
    // The offset can land inside a turn. A checkpoint that added only assistant
    // lines, such as a second commit in one reply, extends a turn that began
    // before the offset.
    for (i in turns.indices.reversed()) {
        if (firstLine[i] <= startLine && startLine <= lastLine[i]) return i
    }
    // The offset is beyond the file: show everything.
    return 0
}
