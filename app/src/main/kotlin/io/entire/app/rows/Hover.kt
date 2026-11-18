package io.entire.app.rows

import io.entire.app.repo.RepoContext
import io.entire.core.checkpoint.SessionPosition
import io.entire.core.format.formatPosition
import io.entire.core.format.parseTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What hovering over a row says: a title when the row has one, then one fact to a line. Plain text. */
data class Hover(val title: String?, val lines: List<String>)

internal const val NO_DATA_HOVER =
    "Entire has no session data for this checkpoint. The commit records the checkpoint's ID, but the data is uploaded separately, " +
        "and this checkpoint's was not found on this machine or on the remote."

internal const val OTHER_CHECKPOINTS_HOVER =
    "Checkpoints that no scanned commit on this branch carries, such as imported checkpoints and checkpoints from other branches."

private val LOCAL_TIME = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

/** A moment as the reader's own clock and language write it. */
internal fun localTime(epochMs: Long): String = LOCAL_TIME.format(Instant.ofEpochMilli(epochMs))

/** The same, for a time as the CLI prints it. Null for text that is no time. */
internal fun localTime(iso: String?): String? = iso?.let { parseTime(it) }?.let { localTime(it) }

/** A hover without its empty lines. Null when nothing is left to say. */
internal fun hover(title: String?, vararg lines: String?): Hover? {
    val kept = lines.filterNotNull().filter { it.isNotEmpty() }
    val heading = title?.ifEmpty { null }
    return if (heading == null && kept.isEmpty()) null else Hover(heading, kept)
}

/** Lines that name a checkpoint's session and say where the checkpoint sits in it. */
internal fun sessionPlace(repo: RepoContext, sessionId: String?, checkpointId: String, known: SessionPosition? = null): List<String> {
    if (sessionId == null) return emptyList()
    val position = known ?: repo.index?.position(sessionId, checkpointId) ?: return emptyList()
    val place = formatPosition(position.ordinal, position.total, position.mayHaveEarlier)
    return listOf(
        "Session $sessionId",
        if (position.mayHaveEarlier) {
            "Checkpoint $place of this session. The Entire CLI lists the checkpoints of the newest 500 commits on this branch, " +
                "so this session may have earlier ones."
        } else {
            "Checkpoint $place of this session."
        },
    )
}
