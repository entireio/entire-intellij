package io.entire.intellij.toolwindow

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.ScrollPaneFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.update.UiNotifyConnector
import io.entire.app.brain.BrainSection
import io.entire.app.brain.BrainViewState
import io.entire.app.setup.StepId
import io.entire.core.brain.BrainDetailKey
import io.entire.intellij.EntireBundle
import io.entire.intellij.EntireService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The Brain tab: what the entire-brain plugin reports about the brain of each
 * repository, and a button that opens the brain's graph in the browser.
 *
 * It is a form and not a tree. Every row of a tree looks clickable, and the
 * brain's details have nothing to click: they are text. Only the buttons act.
 *
 * What the plugin reports is drawn as plain text, so nothing in a report can
 * become markup.
 */
class BrainPanel(project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
    private val service = EntireService.getInstance(project)
    private val body = JPanel().also {
        it.layout = BoxLayout(it, BoxLayout.Y_AXIS)
        it.border = JBUI.Borders.empty(8, 12)
        it.isOpaque = false
    }

    init {
        // The form keeps to the top. Without this, its rows would spread over the height of the tab.
        val top = JPanel(BorderLayout()).also {
            it.add(body, BorderLayout.NORTH)
            it.isOpaque = false
        }
        setContent(ScrollPaneFactory.createScrollPane(top, true))

        service.observe(parent, service.brainSections, ::show)
        // The brain is read when the tab is first on screen, and not before.
        UiNotifyConnector.doWhenFirstShown(this, { service.showBrain() }, parent)
    }

    private fun show(sections: List<BrainSection>) {
        body.removeAll()
        if (sections.isEmpty()) {
            body.add(text(EntireBundle.message("brain.none"), muted = true))
        }
        for ((position, section) in sections.withIndex()) {
            if (sections.size > 1) {
                if (position > 0) body.add(Box.createVerticalStrut(JBUI.scale(14)))
                body.add(left(JBLabel(section.name).also { it.font = JBUI.Fonts.label().asBold() }))
                body.add(Box.createVerticalStrut(JBUI.scale(4)))
            }
            when (section.state) {
                BrainViewState.READY -> listBrain(section)
                BrainViewState.LOADING -> body.add(text(EntireBundle.message("state.loading"), muted = true))
                BrainViewState.PLUGIN_MISSING -> explain(
                    EntireBundle.message("brain.pluginMissing"),
                    button(EntireBundle.message("brain.setUp")) { service.openSetup(StepId.BRAIN) },
                    button(EntireBundle.message("brain.retry")) { service.refresh() },
                )
                BrainViewState.NO_BRAIN -> explain(
                    EntireBundle.message("brain.noBrain"),
                    button(EntireBundle.message("brain.setUp")) { service.openSetup(StepId.BRAIN) },
                    button(EntireBundle.message("brain.retry")) { service.refresh() },
                )
                BrainViewState.FAILED -> explain(
                    EntireBundle.message("brain.failed"),
                    button(EntireBundle.message("brain.retry")) { service.refresh() },
                    button(EntireBundle.message("brain.showLog")) { service.showLog() },
                )
            }
        }
        body.revalidate()
        body.repaint()
    }

    /** The brain of one repository: the buttons, where the graph runs, and what the brain holds. */
    private fun listBrain(section: BrainSection) {
        val buttons = ArrayList<JButton>()
        buttons.add(button(EntireBundle.message("brain.view")) { service.viewBrain(section.root) })
        if (section.running != null) buttons.add(button(EntireBundle.message("brain.stop")) { service.stopBrain(section.root) })
        body.add(row(buttons))
        section.running?.let { body.add(text(EntireBundle.message("brain.running", it), muted = true)) }
        body.add(Box.createVerticalStrut(JBUI.scale(6)))

        // The details are text, not rows of a list: nothing about them can be clicked, so nothing looks as if it could.
        val grid = JPanel(GridBagLayout()).also { it.isOpaque = false }
        for ((line, detail) in section.details.withIndex()) {
            val label = JBLabel(detail.label).also {
                it.foreground = UIUtil.getContextHelpForeground()
                it.toolTipText = detail.tooltip
            }
            val value = text(detail.value, muted = false).also {
                if (detail.attention) it.foreground = WARNING
                it.toolTipText = detail.tooltip
            }
            grid.add(
                label,
                GridBagConstraints().also {
                    it.gridx = 0
                    it.gridy = line
                    it.anchor = GridBagConstraints.NORTHWEST
                    it.insets = JBUI.insets(1, 0, 2, 12)
                },
            )
            grid.add(
                value,
                GridBagConstraints().also {
                    it.gridx = 1
                    it.gridy = line
                    it.weightx = 1.0
                    it.fill = GridBagConstraints.HORIZONTAL
                    it.anchor = GridBagConstraints.NORTHWEST
                    it.insets = JBUI.insets(1, 0, 2, 0)
                },
            )
        }
        body.add(left(grid))
        // A repository that Entire hosts and whose brain is not connected to its hosted brain can be, from the Setup tab.
        if (section.details.any { it.key == BrainDetailKey.HOSTED && it.value.startsWith("Not connected") }) {
            body.add(row(listOf(button(EntireBundle.message("brain.connect")) { service.openSetup(StepId.HOSTED) })))
        }
    }

    /** Says why there is no brain to list, with the buttons that help. */
    private fun explain(message: String, vararg buttons: JButton) {
        body.add(text(message, muted = false))
        body.add(row(buttons.toList()))
    }

    private fun button(label: String, action: () -> Unit): JButton = JButton(label).also { it.addActionListener { action() } }

    private fun row(buttons: List<JButton>): JComponent =
        left(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(4))).also { panel ->
            panel.isOpaque = false
            panel.border = JBUI.Borders.empty()
            buttons.forEach { panel.add(it) }
        })

    /** Text that wraps to the width of the tab, drawn as text and never as markup. */
    private fun text(content: String, muted: Boolean): JBTextArea =
        JBTextArea(content).also {
            it.isEditable = false
            it.lineWrap = true
            it.wrapStyleWord = true
            it.isOpaque = false
            it.border = JBUI.Borders.empty()
            it.font = JBUI.Fonts.label()
            if (muted) it.foreground = UIUtil.getContextHelpForeground()
            it.alignmentX = LEFT_ALIGNMENT
        }

    private fun <T : JComponent> left(component: T): T = component.also { it.alignmentX = LEFT_ALIGNMENT }

    private companion object {
        /** The color of a line that reports something that needs a look. */
        val WARNING: Color = JBColor.namedColor("Label.warningForeground", JBColor(Color(0xA4, 0x6B, 0x00), Color(0xE0, 0xA5, 0x40)))
    }
}
