package io.entire.core.protocol

import io.entire.core.transcript.WireBlock
import io.entire.core.transcript.WireTurn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Transcript turns as the panel's page reads them. A field with no value is
 * left out, and the fields are written in the order the page's types list
 * them.
 */

fun WireBlock.toJson(): JsonObject = when (this) {
    is WireBlock.Text -> buildJsonObject {
        put("kind", "text")
        put("text", text)
    }
    is WireBlock.Note -> buildJsonObject {
        put("kind", "note")
        put("text", text)
    }
    is WireBlock.Image -> buildJsonObject {
        put("kind", "image")
        put("block", block)
        if (mediaType != null) put("mediaType", mediaType)
    }
    is WireBlock.Tool -> buildJsonObject {
        put("kind", "tool")
        put("block", block)
        put("name", name)
        put("summary", summary)
        put("hasBody", hasBody)
        if (status != null) put("status", status)
    }
}

fun WireTurn.toJson(): JsonObject = buildJsonObject {
    put("index", index)
    put("role", role.wire)
    put("blocks", JsonArray(blocks.map { it.toJson() }))
    if (ts != null) put("ts", ts)
}
