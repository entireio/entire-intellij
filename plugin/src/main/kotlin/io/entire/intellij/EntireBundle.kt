package io.entire.intellij

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE = "messages.EntireBundle"

/** The text the plugin shows, from `messages/EntireBundle.properties`. */
internal object EntireBundle {
    private val bundle = DynamicBundle(EntireBundle::class.java, BUNDLE)

    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): @Nls String =
        bundle.getMessage(key, *params)
}
