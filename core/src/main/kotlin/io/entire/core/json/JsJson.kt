package io.entire.core.json

import java.math.BigDecimal
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * JSON as JavaScript reads and writes it.
 *
 * The transcript and the panel's messages were first written for
 * `JSON.parse` and `JSON.stringify`. These helpers keep their behavior where
 * a reader could tell the difference: the order of an object's keys, the way
 * a number is written, and which characters `trim` removes.
 */

/** Parses JSON text, or returns null when it is not JSON. */
fun parseJson(text: String): JsonElement? =
    try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

/** The text of a JSON string, or null for anything else. An empty string is a string. */
fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** The characters JavaScript's `trim` removes. */
fun isJsSpace(c: Char): Boolean =
    c == ' ' || c in '\t'..'\r' || c == '\u00a0' || c == '\u1680' || c in '\u2000'..'\u200a' ||
        c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000' || c == '\ufeff'

fun String.jsTrim(): String = trim(::isJsSpace)

private val ARRAY_INDEX = Regex("0|[1-9][0-9]{0,9}")

/**
 * An object's entries in the order JavaScript lists them: the keys that are
 * array indexes first, in number order, and then the rest in the order they
 * were written.
 */
fun JsonObject.jsEntries(): List<Map.Entry<String, JsonElement>> {
    val (indexes, names) = entries.partition { ARRAY_INDEX.matches(it.key) && it.key.toLong() < 4294967295L }
    return indexes.sortedBy { it.key.toLong() } + names
}

/**
 * A number the way JavaScript writes it: `1.0` is `1`, and a very large or
 * very small one uses an exponent.
 */
internal fun jsNumber(text: String): String {
    val value = text.toDoubleOrNull() ?: return text
    if (value.isNaN() || value.isInfinite()) return "null"
    if (value == 0.0) return "0"
    val sign = if (value < 0) "-" else ""
    val shortest = BigDecimal(Math.abs(value).toString()).stripTrailingZeros()
    val digits = shortest.unscaledValue().toString()
    val k = digits.length
    // The value is 0.digits times ten to the power n.
    val n = k - shortest.scale()
    val body = when {
        n in k..21 -> digits + "0".repeat(n - k)
        n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
        n in -5..0 -> "0." + "0".repeat(-n) + digits
        else -> {
            val exponent = n - 1
            val mantissa = if (k == 1) digits else digits.substring(0, 1) + "." + digits.substring(1)
            mantissa + "e" + (if (exponent < 0) "-" else "+") + Math.abs(exponent)
        }
    }
    return sign + body
}

private fun quote(text: String, out: StringBuilder) {
    out.append('"')
    for (c in text) {
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\b' -> out.append("\\b")
            c == '\u000c' -> out.append("\\f")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> out.append(c)
        }
    }
    out.append('"')
}

private fun write(value: JsonElement, indent: String, depth: Int, out: StringBuilder) {
    when (value) {
        is JsonNull -> out.append("null")
        is JsonPrimitive -> if (value.isString) quote(value.content, out) else when (value.content) {
            "true", "false" -> out.append(value.content)
            else -> out.append(jsNumber(value.content))
        }
        is JsonArray -> writeItems(value.size, '[', ']', indent, depth, out) { index ->
            write(value[index], indent, depth + 1, out)
        }
        is JsonObject -> {
            val entries = value.jsEntries()
            writeItems(entries.size, '{', '}', indent, depth, out) { index ->
                quote(entries[index].key, out)
                out.append(if (indent.isEmpty()) ":" else ": ")
                write(entries[index].value, indent, depth + 1, out)
            }
        }
    }
}

private inline fun writeItems(count: Int, open: Char, close: Char, indent: String, depth: Int, out: StringBuilder, item: (Int) -> Unit) {
    out.append(open)
    if (count == 0) {
        out.append(close)
        return
    }
    for (index in 0 until count) {
        if (index > 0) out.append(',')
        if (indent.isNotEmpty()) out.append('\n').append(indent.repeat(depth + 1))
        item(index)
    }
    if (indent.isNotEmpty()) out.append('\n').append(indent.repeat(depth))
    out.append(close)
}

/** `JSON.stringify(value)`, or `JSON.stringify(value, null, 2)` when `indent` is two spaces. */
fun stringify(value: JsonElement, indent: String = ""): String = StringBuilder().also { write(value, indent, 0, it) }.toString()
