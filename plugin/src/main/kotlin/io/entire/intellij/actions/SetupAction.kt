package io.entire.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.wm.ToolWindowManager
import io.entire.intellij.EntireService
import io.entire.intellij.toolwindow.EntireToolWindowFactory

/** Opens the Setup tab, which says what is missing for Entire in the repository and sets it up step by step. */
class SetupAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { EntireService.getInstance(it).openSetup(null) }
    }

    override fun update(event: AnActionEvent) {
        // The Setup tab is in the Entire tool window, which a project without a Git repository does not have.
        val project = event.project
        event.presentation.isEnabled = project != null &&
            ToolWindowManager.getInstance(project).getToolWindow(EntireToolWindowFactory.ID)?.isAvailable == true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
