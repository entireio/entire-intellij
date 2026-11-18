package io.entire.app.rows

import java.util.Locale

/** What a tab shows under a filter, and the rows to open so that the matches inside them are in view. */
class FilteredRows(
    val rows: List<RowNode>,
    /** How many rows match, not counting the rows kept only because a row under them matches. */
    val matches: Int,
    /** Rows kept because a row under them matches. They are opened, so the match is in view. */
    val opened: Set<String>,
)

/** The words of a filter, in lower case. Empty for a filter of only spaces. */
fun filterWords(text: String): List<String> = text.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }

/** Everything a row can be found by: its text, and the IDs that the text shortens or leaves out. */
private fun haystack(row: RowNode): String =
    listOfNotNull(
        row.label,
        row.description,
        row.commitSha,
        row.checkpointId,
        row.sessionId,
        row.trail?.let { "#${it.number}" },
    ).joinToString("\n").lowercase(Locale.ROOT)

/**
 * The rows that match every word of a filter, as typed into a tab.
 *
 * A row that matches keeps all of its rows. A row that does not match stays
 * when a row under it matches, and then keeps only those. Load More rows stay
 * at the end of their lists, so a list can still be read further, and say
 * how far the filter has looked. The rows of Entire's search results stay as
 * they are: they already answer a search.
 */
fun filterRows(rows: List<RowNode>, text: String): FilteredRows {
    val words = filterWords(text)
    if (words.isEmpty()) return FilteredRows(rows, 0, emptySet())
    var matches = 0
    val opened = HashSet<String>()

    fun visit(siblings: List<RowNode>): List<RowNode> {
        val kept = ArrayList<RowNode>()
        // The rows a Load More row of these siblings counts.
        val listed = siblings.count { it.kind != RowKind.LOAD_MORE && it.kind != RowKind.MESSAGE && it.kind != RowKind.SEARCH_GROUP }
        var found = 0
        for (row in siblings) {
            when {
                row.kind == RowKind.SEARCH_GROUP -> kept.add(row)
                row.kind == RowKind.LOAD_MORE -> {
                    val noun = row.label.removePrefix(LOAD_MORE)
                    kept.add(
                        row.copy(
                            label = "Search the next $noun",
                            description = "$found ${if (found == 1) "match" else "matches"} in ${count(listed)} $noun read",
                            // Loading on scroll would read the whole history, a page at a time, while the filter shows little.
                            auto = false,
                        ),
                    )
                }
                // A message says something about the list as it was. Under a filter it says nothing about the matches.
                row.kind == RowKind.MESSAGE -> {}
                words.all { it in haystack(row) } -> {
                    matches += 1
                    found += 1
                    kept.add(row)
                }
                else -> {
                    val children = visit(row.children)
                    if (children.any { it.kind != RowKind.LOAD_MORE && it.kind != RowKind.SEARCH_GROUP }) {
                        found += 1
                        opened.add(row.id)
                        kept.add(row.copy(children = children))
                    }
                }
            }
        }
        return kept
    }

    return FilteredRows(visit(rows), matches, opened)
}
