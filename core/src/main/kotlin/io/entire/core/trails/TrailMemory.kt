package io.entire.core.trails

import io.entire.core.cli.TrailChange
import io.entire.core.cli.TrailDetail
import io.entire.core.cli.TrailTimeline

/** The first page of a change's timeline, with how it was asked for. */
class RememberedTimeline(
    /** A later page is asked for the same way. */
    val of: TimelineOf,
    val page: TrailTimeline,
)

class RememberedTrail(
    val detail: TrailDetail,
    /** First pages of the timelines read so far, by change. */
    val timelines: MutableMap<String, RememberedTimeline>,
)

private fun changeKey(change: TrailChange): String = "${change.repository}/${change.number}"

/**
 * The trails read in one project, most recently used last.
 *
 * Each CLI call for a trail takes seconds, so a trail opened a second time is
 * drawn from here at once. The panel still reads it again and redraws what
 * has changed: what is kept here is a head start and not a source of truth.
 */
class TrailMemory(private val capacity: Int = 40) {
    private val trails = LinkedHashMap<Long, RememberedTrail>()

    val size: Int get() = trails.size

    /** What is kept about a trail. Asking counts as a use, so the trail is kept longer. */
    fun get(trail: Long): RememberedTrail? {
        val found = trails.remove(trail) ?: return null
        trails[trail] = found
        return found
    }

    /** The trail's ID on Entire, when a read has reported it. */
    fun idOf(trail: Long): String? = trails[trail]?.detail?.id

    /** Keeps a trail as just read. Its timelines stay for the changes it still has. */
    fun keepDetail(detail: TrailDetail) {
        val before = trails[detail.number]
        val timelines = LinkedHashMap<String, RememberedTimeline>()
        for (change in detail.changes) {
            before?.timelines?.get(changeKey(change))?.let { timelines[changeKey(change)] = it }
        }
        trails.remove(detail.number)
        trails[detail.number] = RememberedTrail(detail, timelines)
        while (trails.size > capacity) trails.remove(trails.keys.first())
    }

    /** Keeps the first page of a change's timeline. Ignored for a trail that is not kept. */
    fun keepTimeline(trail: Long, change: TrailChange, timeline: RememberedTimeline) {
        trails[trail]?.timelines?.put(changeKey(change), timeline)
    }

    fun timeline(trail: Long, change: TrailChange): RememberedTimeline? = trails[trail]?.timelines?.get(changeKey(change))

    fun forget(trail: Long) {
        trails.remove(trail)
    }

    fun clear() = trails.clear()
}
