package io.entire.app.rows

import io.entire.core.protocol.DetailTarget

/** A view whose rows open the detail panel. Each call may come from any thread. */
interface FollowedView {
    /** Whether the view is on screen. */
    suspend fun visible(): Boolean

    suspend fun selected(): RowNode?

    /** Selects the row at the end of `path`, which lists the IDs of the rows from the top of the tree down to it. */
    suspend fun select(path: List<String>)
}

/**
 * Moves a view's selection to what the detail tab shows.
 *
 * A click selects a row and opens the tab on it. The tab can then move on by
 * itself: Previous and Next step through a session's checkpoints. The row
 * that was clicked would stay selected, under a tab that now shows something
 * else. So when a tab moves, the view selects the row for what the tab shows
 * now.
 *
 * A view that is not on screen is left alone. Call `follow` on the manager's
 * own thread.
 */
class SelectionFollower(private val view: FollowedView, private val rowPath: suspend (DetailTarget, Boolean) -> List<String>?) {
    /** Counts the moves, so that a search for an earlier one does not select its row after a later one has. */
    private var moves = 0

    suspend fun follow(target: DetailTarget) {
        val move = ++moves
        if (!view.visible()) return
        val selected = view.selected()
        if (selected?.standsFor(target) == true) return
        // From the row of one session of a checkpoint, the selection moves to the row of that session under the next checkpoint.
        val path = rowPath(target, selected?.kind == RowKind.SLOT) ?: return
        if (move != moves || !view.visible()) return
        view.select(path)
    }
}
