package io.entire.core.git

/** Legacy checkpoint ID: 12 lowercase hex characters. */
private val HEX_ID = Regex("[0-9a-f]{12}")

/** Current checkpoint ID: a 26-character uppercase Crockford base32 ULID. */
private val ULID_ID = Regex("[0-9A-HJKMNP-TV-Z]{26}")

private val SHA1 = Regex("[0-9a-f]{40}")
private val SHA256 = Regex("[0-9a-f]{64}")

// `matches` takes the whole text. A pattern that ends in `$` would also accept a trailing line break in Java.
fun isCheckpointId(value: String): Boolean = HEX_ID.matches(value) || ULID_ID.matches(value)

fun isCommitSha(value: String): Boolean = SHA1.matches(value) || SHA256.matches(value)

/** What JavaScript's `\s` matches. Java's `\s` is ASCII only. */
private const val SPACE = "[\\s\\u00a0\\ufeff\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000]"

/**
 * The Entire CLI's own pattern for a checkpoint link (`trailers.go`): the key
 * anywhere in the message, one ID, then whitespace or the end.
 *
 * It is deliberately not git's definition of a trailer. Git reads trailers
 * from the last paragraph only, and a squash merge joins the messages of the
 * commits it squashed, so each `Entire-Checkpoint` line ends up in a paragraph
 * of its own. Reading only git's trailer block would drop all but the last.
 */
private val CHECKPOINT_LINK = Regex("Entire-Checkpoint:$SPACE*([0-9a-f]{12}|[0-9A-HJKMNP-TV-Z]{26})(?:$SPACE|\\z)")

/** Every checkpoint ID a commit message links to, in order, without duplicates. */
fun parseCheckpointIds(message: String): List<String> {
    val ids = ArrayList<String>()
    for (match in CHECKPOINT_LINK.findAll(message)) {
        val id = match.groupValues[1]
        if (id.isNotEmpty() && id !in ids) ids.add(id)
    }
    return ids
}

data class CommitRecord(
    val sha: String,
    val shortSha: String,
    /** Author time in seconds since the epoch. */
    val authorTime: Long,
    val author: String,
    val subject: String,
    /** Every checkpoint the commit message links to, in order, without duplicates. */
    val checkpointIds: List<String>,
)

private const val FIELD = '\u001f'
private const val RECORD = '\u0000'

/**
 * `git log` format that pairs with [parseLogRecords]. Use it with `-z`,
 * which ends each record with NUL. The last field is the whole message.
 */
val LOG_FORMAT: String = listOf("%H", "%h", "%at", "%aN", "%s", "%B").joinToString("%x1f")

private val LEADING_INTEGER = Regex("^\\s*([+-]?\\d+)")

fun parseLogRecords(output: String): List<CommitRecord> {
    val records = ArrayList<CommitRecord>()
    for (raw in output.split(RECORD)) {
        if (raw.isBlank()) continue
        val fields = raw.split(FIELD)
        val sha = fields[0]
        if (!isCommitSha(sha)) continue
        records.add(
            CommitRecord(
                sha = sha,
                shortSha = fields.getOrNull(1).orEmpty().ifEmpty { sha.take(7) },
                authorTime = LEADING_INTEGER.find(fields.getOrNull(2).orEmpty())?.groupValues?.get(1)?.toLongOrNull() ?: 0,
                author = fields.getOrNull(3).orEmpty(),
                subject = fields.getOrNull(4).orEmpty(),
                // A message can itself contain the field separator, so rejoin the rest.
                checkpointIds = parseCheckpointIds(fields.drop(5).joinToString(FIELD.toString())),
            ),
        )
    }
    return records
}
