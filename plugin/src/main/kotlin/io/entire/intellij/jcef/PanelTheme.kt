package io.entire.intellij.jcef

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The IDE's colors and fonts under the names the panel's stylesheet reads.
 *
 * The stylesheet was written for VS Code's theme variables. Each one gets the
 * closest thing the IDE has. The build writes the list of names the
 * stylesheet uses to `theme-variables.json`, and every name on it has to be
 * given a value here.
 */
object PanelTheme {
    private fun css(color: Color): String =
        if (color.alpha == 255) "rgb(${color.red}, ${color.green}, ${color.blue})" else "rgba(${color.red}, ${color.green}, ${color.blue}, ${color.alpha / 255.0})"

    private fun family(name: String, generic: String): String = "'" + name.replace("'", "") + "', " + generic

    fun variables(): Map<String, String> {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val background = scheme.defaultBackground
        val foreground = UIUtil.getLabelForeground()
        val dark = ColorUtil.isDark(background)
        val border = JBColor.border()
        val muted = UIUtil.getContextHelpForeground()
        val link = JBUI.CurrentTheme.Link.Foreground.ENABLED
        // A light tint of the text color over the background, for code and quotes.
        val tint = ColorUtil.mix(background, foreground, 0.07)
        val button = JBUI.CurrentTheme.Button.defaultButtonColorStart()
        val buttonText = JBColor.namedColor("Button.default.foreground", Color.WHITE)
        val secondary = JBUI.CurrentTheme.Button.buttonColorStart()
        val secondaryText = JBColor.namedColor("Button.foreground", foreground)
        // The IDE names no chart or test colors, so these are its usual green, red, yellow, and blue for each kind of theme.
        val green = if (dark) Color(0x57, 0x96, 0x5c) else Color(0x36, 0x87, 0x46)
        val red = if (dark) Color(0xdb, 0x5c, 0x5c) else Color(0xdb, 0x3b, 0x4b)
        val yellow = if (dark) Color(0xd6, 0xae, 0x58) else Color(0xa4, 0x67, 0x04)
        val blue = if (dark) Color(0x54, 0x8a, 0xf7) else Color(0x35, 0x74, 0xf0)
        val label = JBUI.Fonts.label()
        return linkedMapOf(
            "--vscode-foreground" to css(foreground),
            "--vscode-descriptionForeground" to css(muted),
            "--vscode-errorForeground" to css(red),
            "--vscode-focusBorder" to css(JBUI.CurrentTheme.Focus.focusColor()),
            "--vscode-panel-border" to css(border),
            "--vscode-widget-border" to css(border),
            "--vscode-editor-background" to css(background),
            "--vscode-editor-font-family" to family(scheme.editorFontName, "monospace"),
            "--vscode-editor-font-size" to "${scheme.editorFontSize}px",
            "--vscode-font-family" to family(label.family, "system-ui, sans-serif"),
            "--vscode-font-size" to "${label.size}px",
            "--vscode-textLink-foreground" to css(link),
            "--vscode-textLink-activeForeground" to css(JBUI.CurrentTheme.Link.Foreground.HOVERED),
            "--vscode-textCodeBlock-background" to css(tint),
            "--vscode-textBlockQuote-background" to css(tint),
            "--vscode-textBlockQuote-border" to css(border),
            "--vscode-button-background" to css(button),
            "--vscode-button-foreground" to css(buttonText),
            "--vscode-button-border" to css(JBUI.CurrentTheme.Button.buttonOutlineColorStart(true)),
            "--vscode-button-hoverBackground" to css(ColorUtil.mix(button, foreground, 0.12)),
            "--vscode-button-secondaryBackground" to css(secondary),
            "--vscode-button-secondaryForeground" to css(secondaryText),
            "--vscode-button-secondaryHoverBackground" to css(ColorUtil.mix(secondary, foreground, 0.12)),
            "--vscode-charts-green" to css(green),
            "--vscode-charts-red" to css(red),
            "--vscode-charts-yellow" to css(yellow),
            "--vscode-testing-iconPassed" to css(green),
            "--vscode-testing-iconFailed" to css(red),
            "--vscode-testing-iconQueued" to css(yellow),
            "--vscode-gitDecoration-addedResourceForeground" to css(green),
            "--vscode-gitDecoration-deletedResourceForeground" to css(red),
            "--vscode-editorInfo-foreground" to css(blue),
            "--entire-scrollbar" to css(ColorUtil.withAlpha(foreground, 0.25)),
        )
    }

    fun json(): JsonObject = JsonObject(variables().mapValues { JsonPrimitive(it.value) })
}
