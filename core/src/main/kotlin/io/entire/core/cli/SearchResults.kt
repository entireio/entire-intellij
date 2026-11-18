package io.entire.core.cli

import io.entire.core.git.isCheckpointId
import io.entire.core.git.isCommitSha
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * What `entire search --json` prints: one page of what Entire's search
 * service found for a query in the current repository. Each result names its
 * type, and carries the fields of that type under `data`.
 *
 * Commits and sessions are the types seen so far. Checkpoints are counted by
 * the service too, and read here by the names the other two use. Pull
 * requests and repositories are counted and dropped: the plugin has nothing
 * to open them in.
 */

/** Hits on one page. Entire's own default is 10, and it accepts more. */
const val SEARCH_PAGE = 20

sealed interface SearchHit {
    /** Which branch the hit belongs to. A hit can be on a branch the clone does not have. */
    val branch: String?
    val author: String?

    /** An ISO time. */
    val createdAt: String?

    data class Commit(
        val sha: String,
        val subject: String,
        override val branch: String? = null,
        override val author: String? = null,
        override val createdAt: String? = null,
        val additions: Long? = null,
        val deletions: Long? = null,
        val filesChanged: Long? = null,
        /** The commit's page on its forge, when Entire knows it. */
        val url: String? = null,
    ) : SearchHit

    data class Checkpoint(
        val checkpointId: String,
        val title: String? = null,
        val sessionId: String? = null,
        val commitSha: String? = null,
        override val branch: String? = null,
        override val author: String? = null,
        override val createdAt: String? = null,
    ) : SearchHit

    data class Session(
        val sessionId: String,
        /** The name entire.com shows for the session. */
        val name: String? = null,
        /** The prompt that opened the session. */
        val prompt: String? = null,
        val agent: String? = null,
        val model: String? = null,
        override val branch: String? = null,
        override val author: String? = null,
        override val createdAt: String? = null,
        /** The checkpoint whose content matched the query. */
        val matchedCheckpointId: String? = null,
        val steps: Long? = null,
    ) : SearchHit
}

data class SearchPage(
    val hits: List<SearchHit>,
    /** How many results the query has in all, of every type. */
    val total: Long,
    /** 1-based. */
    val page: Int,
    val totalPages: Int,
)

private fun whole(value: JsonElement?): Long? = num(value)?.takeIf { it % 1.0 == 0.0 && it >= 0 }?.toLong()

private fun readHit(type: String?, data: JsonObject): SearchHit? =
    when (type) {
        "commit" -> {
            val sha = (str(data["commitSha"]) ?: str(data["id"]))?.takeIf { isCommitSha(it) }
            if (sha == null) {
                null
            } else {
                SearchHit.Commit(
                    sha = sha,
                    subject = str(data["commitSubject"]) ?: str(data["commitMessage"])?.lineSequence()?.firstOrNull().orEmpty(),
                    branch = str(data["branch"]),
                    author = str(data["author"]) ?: str(data["authorUsername"]),
                    createdAt = str(data["createdAt"]),
                    additions = whole(data["additions"]),
                    deletions = whole(data["deletions"]),
                    filesChanged = whole(data["filesChanged"]),
                    url = str(data["htmlUrl"])?.takeIf { it.startsWith("https://") },
                )
            }
        }
        "checkpoint" -> {
            val id = (str(data["checkpointId"]) ?: str(data["id"]))?.takeIf { isCheckpointId(it) }
            if (id == null) {
                null
            } else {
                SearchHit.Checkpoint(
                    checkpointId = id,
                    title = str(data["title"]) ?: str(data["displayName"]) ?: str(data["commitSubject"]) ?: str(data["prompt"]),
                    sessionId = str(data["sessionId"]),
                    commitSha = str(data["commitSha"])?.takeIf { isCommitSha(it) },
                    branch = str(data["branch"]),
                    author = str(data["author"]) ?: str(data["authorUsername"]),
                    createdAt = str(data["createdAt"]),
                )
            }
        }
        "session" -> {
            val id = str(data["sessionId"]) ?: str(data["id"])
            if (id == null) {
                null
            } else {
                SearchHit.Session(
                    sessionId = id,
                    name = str(data["displayName"]),
                    prompt = str(data["prompt"]),
                    agent = str(data["agent"]),
                    model = str(data["model"]),
                    branch = str(data["branch"]),
                    author = str(data["author"]) ?: str(data["authorUsername"]),
                    createdAt = str(data["createdAt"]),
                    matchedCheckpointId = str(data["matchedCheckpointId"])?.takeIf { isCheckpointId(it) },
                    steps = whole(data["stepCount"]),
                )
            }
        }
        // Pull requests and repositories have nowhere to open in the plugin.
        else -> null
    }

fun readSearchPage(value: JsonElement?): SearchPage? {
    val json = value as? JsonObject ?: return null
    if (json["results"] !is JsonArray) return null
    val hits = objects(json["results"]).mapNotNull { result ->
        val data = result["data"] as? JsonObject ?: return@mapNotNull null
        readHit(str(result["type"]), data)
    }
    return SearchPage(
        hits = hits,
        total = whole(json["total"]) ?: hits.size.toLong(),
        page = whole(json["page"])?.toInt()?.coerceAtLeast(1) ?: 1,
        totalPages = whole(json["total_pages"])?.toInt() ?: 1,
    )
}
