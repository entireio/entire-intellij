package io.entire.app.rows

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class RowFilterTest {
    private val root = "/r"

    private fun row(id: String, kind: RowKind, label: String, description: String = "", children: List<RowNode> = emptyList(), checkpointId: String? = null) =
        RowNode(id = id, kind = kind, label = label, description = description, repoRoot = root, children = children, checkpointId = checkpointId)

    private val probe = row("a", RowKind.COMMIT, "fix: redirect probe", children = listOf(row("a1", RowKind.CHECKPOINT, "Session one")))
    private val docs = row(
        "b",
        RowKind.COMMIT,
        "docs: readme",
        children = listOf(row("b1", RowKind.CHECKPOINT, "redirect handling", checkpointId = "0123456789ab"), row("b2", RowKind.CHECKPOINT, "typos")),
    )
    private val chore = row("c", RowKind.COMMIT, "chore: bump")
    private val message = row("m", RowKind.MESSAGE, "Showing the commits of main.")
    private val more = RowNode(id = "more", kind = RowKind.LOAD_MORE, label = "Load more commits", description = "3 shown", repoRoot = root, list = "commits", auto = true)
    private val rows = listOf(probe, docs, chore, message, more)

    @Test
    fun `keeps matching rows whole, and parents with only their matching rows`() {
        val filtered = filterRows(rows, "REDIRECT")
        assertEquals(listOf("a", "b", "more"), filtered.rows.map { it.id })
        assertEquals(probe, filtered.rows[0])
        assertEquals(listOf("b1"), filtered.rows[1].children.map { it.id })
        assertEquals(setOf("b"), filtered.opened)
        assertEquals(listOf(2), listOf(filtered.matches))
    }

    @Test
    fun `a Load More row says how far the filter has looked, and waits for a click`() {
        val load = filterRows(rows, "redirect").rows.last()
        assertEquals("Search the next commits", load.label)
        assertEquals("2 matches in 3 commits read", load.description)
        assertFalse(load.auto)
    }

    @Test
    fun `every word has to match, in any case`() {
        assertEquals(listOf("a", "more"), filterRows(rows, "Probe  redirect").rows.map { it.id })
    }

    @Test
    fun `finds rows by the IDs their text leaves out`() {
        val filtered = filterRows(rows, "0123456789ab")
        assertEquals(listOf("b", "more"), filtered.rows.map { it.id })
        assertEquals(listOf("b1"), filtered.rows[0].children.map { it.id })
    }

    @Test
    fun `no words leaves the rows as they are`() {
        assertEquals(rows, filterRows(rows, "   ").rows)
    }

    @Test
    fun `Entire's search results stay under any filter`() {
        val group = row("s", RowKind.SEARCH_GROUP, "On Entire", children = listOf(row("s1", RowKind.SEARCH_HIT, "something else")))
        assertEquals(listOf("more", "s"), filterRows(rows + group, "zzz").rows.map { it.id })
    }
}
