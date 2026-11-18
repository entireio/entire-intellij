package io.entire.core.web

import java.net.URI
import java.net.URISyntaxException

/** A repository that Entire hosts, as addressed by an `entire://` remote. */
data class EntireRepo(
    /** `gh` for a GitHub mirror, `et` for an Entire-native repository. */
    val forge: String,
    /** GitHub owner, or Entire project. */
    val owner: String,
    val repo: String,
)

const val DEFAULT_WEB_BASE = "https://entire.com"

private val ENTIRE_REMOTE = Regex("entire://([^/\\s]+)/(gh|et)/([^/\\s]+)/([^/\\s]+?)/?")
private val DOT_GIT = Regex("\\.git$", RegexOption.IGNORE_CASE)

/**
 * Parses `entire://<cluster-host>/<forge>/<owner-or-project>/<repo>[.git]`.
 *
 * Only this scheme proves that Entire hosts the repository. A github.com
 * remote might be connected through the GitHub App, and nothing in a local
 * clone says whether it is, so it gets no links.
 */
fun parseEntireRemote(remoteUrl: String?): EntireRepo? {
    if (remoteUrl.isNullOrEmpty()) return null
    val match = ENTIRE_REMOTE.matchEntire(remoteUrl.trim()) ?: return null
    val owner = match.groupValues[3]
    val repo = match.groupValues[4].replace(DOT_GIT, "")
    if (owner.isEmpty() || repo.isEmpty()) return null
    return EntireRepo(match.groupValues[2], owner, repo)
}

/**
 * Picks the web origin: the setting, then `ENTIRE_WEB_BASE_URL`, then
 * entire.com. A value that is not an http(s) URL is ignored.
 */
fun resolveWebBase(setting: String?, env: Map<String, String>): String =
    webBaseOf(setting) ?: webBaseOf(env["ENTIRE_WEB_BASE_URL"]) ?: DEFAULT_WEB_BASE

/**
 * The web origin that a setting names, or null when the text is empty or is
 * not an http(s) URL with a host.
 */
fun webBaseOf(text: String?): String? {
    val value = text?.trim()
    if (value.isNullOrEmpty()) return null
    val uri = try {
        URI(value)
    } catch (e: URISyntaxException) {
        return null
    }
    val scheme = uri.scheme?.lowercase()
    val host = uri.host
    if ((scheme != "https" && scheme != "http") || host.isNullOrEmpty()) return null
    // The address without a query or a fragment, and without the port a scheme implies.
    val implied = if (scheme == "https") 443 else 80
    val port = if (uri.port < 0 || uri.port == implied) "" else ":${uri.port}"
    return "$scheme://${host.lowercase()}$port${uri.rawPath.orEmpty().trimEnd('/')}"
}

private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

/** JavaScript's `encodeURIComponent`. `URLEncoder` writes a space as a plus sign, which a path does not read as a space. */
fun encodeUriComponent(text: String): String {
    val out = StringBuilder()
    for (byte in text.toByteArray(Charsets.UTF_8)) {
        val c = (byte.toInt() and 0xff).toChar()
        if (c in UNRESERVED) out.append(c) else out.append('%').append("%02X".format(byte.toInt() and 0xff))
    }
    return out.toString()
}

fun repoUrl(base: String, repo: EntireRepo): String = "$base/${repo.forge}/${encodeUriComponent(repo.owner)}/${encodeUriComponent(repo.repo)}"

/** Checkpoints have no page of their own. The commit page is the way in. */
fun commitUrl(base: String, repo: EntireRepo, sha: String): String = "${repoUrl(base, repo)}/commit/${encodeUriComponent(sha)}"

fun sessionUrl(base: String, repo: EntireRepo, sessionId: String): String = "${repoUrl(base, repo)}/session/${encodeUriComponent(sessionId)}"
