package io.entire.intellij.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/**
 * What the user set for the plugin. The settings are the IDE's, and not one
 * project's: the Entire CLI is installed once on a machine.
 *
 * The path and the address describe this machine, so the file they are in
 * stays out of settings sync.
 */
@Service(Service.Level.APP)
@State(name = "EntireSettings", storages = [Storage("entire.xml", roamingType = RoamingType.DISABLED)])
class EntireSettings : SimplePersistentStateComponent<EntireSettings.Stored>(Stored()) {
    class Stored : BaseState() {
        var cliPath by string()
        var webBaseUrl by string()
        var trailsEnabled by property(true)
        var loadOnScroll by property(true)
    }

    /** The path to the Entire CLI, or null to search for it. */
    var cliPath: String?
        get() = state.cliPath?.takeIf { it.isNotBlank() }
        set(value) {
            state.cliPath = value?.trim()?.ifEmpty { null }
        }

    /** The base of links to the Entire web app, or null for the default. */
    var webBaseUrl: String?
        get() = state.webBaseUrl?.takeIf { it.isNotBlank() }
        set(value) {
            state.webBaseUrl = value?.trim()?.ifEmpty { null }
        }

    /** Whether trails may be read. They are read from Entire over the network, so they can be turned off. */
    var trailsEnabled: Boolean
        get() = state.trailsEnabled
        set(value) {
            state.trailsEnabled = value
        }

    /** Whether a list loads its next rows when its Load More row scrolls into view. When off, a click loads them. */
    var loadOnScroll: Boolean
        get() = state.loadOnScroll
        set(value) {
            state.loadOnScroll = value
        }

    companion object {
        fun getInstance(): EntireSettings = service()
    }
}
