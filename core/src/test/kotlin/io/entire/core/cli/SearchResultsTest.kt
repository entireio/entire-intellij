package io.entire.core.cli

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SearchResultsTest {
    private val sha = "0123456789abcdef0123456789abcdef01234567"

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `reads commits and sessions, and drops results with nowhere to open`() {
        val page = readSearchPage(
            json(
                """
                {"results": [
                  {"type": "commit", "data": {"commitSha": "$sha", "commitSubject": "fix: sweep clock skew", "branch": "main", "author": "ada",
                    "createdAt": "2026-11-01T10:00:00Z", "additions": 3, "deletions": 1, "filesChanged": 2,
                    "htmlUrl": "https://github.com/o/r/commit/$sha"}, "searchMeta": {}},
                  {"type": "session", "data": {"sessionId": "s-1", "displayName": "Sweep the skew", "prompt": "fix the skew", "agent": "Claude Code",
                    "authorUsername": "ada", "matchedCheckpointId": "a1b2c3d4e5f6", "stepCount": 7}},
                  {"type": "pr", "data": {"id": "12"}},
                  {"type": "repo", "data": {"id": "o/r"}}
                ], "total": 4, "page": 2, "total_pages": 3, "counts": {"commit": 1, "session": 1}}
                """,
            ),
        )
        assertEquals(
            SearchPage(
                hits = listOf(
                    SearchHit.Commit(
                        sha = sha, subject = "fix: sweep clock skew", branch = "main", author = "ada", createdAt = "2026-11-01T10:00:00Z",
                        additions = 3, deletions = 1, filesChanged = 2, url = "https://github.com/o/r/commit/$sha",
                    ),
                    SearchHit.Session(
                        sessionId = "s-1", name = "Sweep the skew", prompt = "fix the skew", agent = "Claude Code", author = "ada",
                        matchedCheckpointId = "a1b2c3d4e5f6", steps = 7,
                    ),
                ),
                total = 4,
                page = 2,
                totalPages = 3,
            ),
            page,
        )
    }

    @Test
    fun `drops hits whose IDs are not IDs, and links that are not https`() {
        val page = readSearchPage(
            json(
                """
                {"results": [
                  {"type": "commit", "data": {"commitSha": "not-a-sha", "commitSubject": "x"}},
                  {"type": "commit", "data": {"commitSha": "$sha", "commitMessage": "first line\nsecond line", "htmlUrl": "javascript:alert(1)"}},
                  {"type": "checkpoint", "data": {"checkpointId": "nope"}},
                  {"type": "session", "data": {"prompt": "no id"}}
                ]}
                """,
            ),
        )
        assertEquals(SearchPage(listOf(SearchHit.Commit(sha = sha, subject = "first line")), total = 1, page = 1, totalPages = 1), page)
    }

    @Test
    fun `an answer without results is not a page`() {
        assertNull(readSearchPage(json("""{"total": 0}""")))
        assertNull(readSearchPage(json("""[]""")))
        assertNull(readSearchPage(null))
    }

    @Test
    fun `the query follows a double dash, so it cannot be read as a flag`() {
        val args = searchArgs("--all-repos", 20, 2)
        assertEquals(listOf("search", "--json", "--limit", "20", "--page", "2", "--", "--all-repos"), args)
        assertTrue(isReadCommand(args))
        assertFalse(isReadCommand(listOf("search", "--json", "--all-repos")))
        assertFalse(isReadCommand(listOf("search", "--json", "--limit", "x", "--page", "1", "--", "q")))
    }

    @Test
    fun `reads the login`() {
        assertEquals(AuthStatus(loggedIn = true, user = "Ada L"), readAuthStatus(json("""{"logged_in": true, "user": "ada", "display_name": "Ada L"}""")))
        assertEquals(AuthStatus(loggedIn = true, user = "ada"), readAuthStatus(json("""{"logged_in": true, "user": "ada"}""")))
        assertEquals(AuthStatus(loggedIn = false, user = null), readAuthStatus(json("""{"logged_in": false, "user": "ada"}""")))
        assertNull(readAuthStatus(json("""{"user": "ada"}""")))
    }

    @Test
    fun `reads the agent list, with the agents this repository has hooks for`() {
        assertEquals(
            listOf(AgentChoice("antigravity", false), AgentChoice("claude-code", true), AgentChoice("codex", true)),
            readAgentList("Agents:\n    antigravity\n  ✓ claude-code\n  ✓ codex (default)\n"),
        )
        assertNull(readAgentList(""))
        assertNull(readAgentList("Agents:\n  -rf\n"))
    }
}
