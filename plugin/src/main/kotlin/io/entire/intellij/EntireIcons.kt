package io.entire.intellij

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/** The plugin's own icons. The IDE picks the `_dark` file in a dark theme. */
object EntireIcons {
    /** The Entire mark, 16 by 16, as on the tool window's button. */
    @JvmField
    val Entire: Icon = IconLoader.getIcon("/icons/toolWindowEntire.svg", EntireIcons::class.java)
}
