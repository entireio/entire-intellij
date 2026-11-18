package io.entire.core.cli

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** `entire auth status --json`, as far as the plugin uses it. */
data class AuthStatus(
    val loggedIn: Boolean,
    /** Who is logged in: the account's display name, or else its user name. Null when no one is. */
    val user: String? = null,
)

fun readAuthStatus(value: JsonElement?): AuthStatus? {
    val json = value as? JsonObject ?: return null
    val loggedIn = bool(json["logged_in"]) ?: return null
    return AuthStatus(loggedIn = loggedIn, user = if (loggedIn) str(json["display_name"]) ?: str(json["user"]) else null)
}
