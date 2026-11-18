package io.entire.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import io.entire.app.brain.BrainSection
import io.entire.intellij.EntireBundle
import io.entire.intellij.EntireService

/**
 * Opens the graph of a repository's brain in the browser. With one
 * repository there is nothing to ask. With several, a list asks which.
 */
class ViewBrainAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = EntireService.getInstance(project)
        val sections = service.brainSections.value
        val only = sections.singleOrNull()
        if (only != null) {
            service.viewBrain(only.root)
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(sections)
            .setTitle(EntireBundle.message("brain.choose"))
            .setRenderer { _, section: BrainSection, _, _, _ -> javax.swing.JLabel(section.name) }
            .setItemChosenCallback { service.viewBrain(it.root) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    override fun update(event: AnActionEvent) {
        // The sections exist once the repositories have been read and Entire is enabled in one.
        val project = event.project
        event.presentation.isEnabled = project != null && EntireService.getInstance(project).brainSections.value.isNotEmpty()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/** Stops every brain graph server the project started. */
class StopBrainGraphAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { EntireService.getInstance(it).stopBrain() }
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible = project != null && EntireService.getInstance(project).brainRunning()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
