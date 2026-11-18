package io.entire.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import io.entire.intellij.EntireService

/** Looks at the project's repositories and the Entire CLI again. */
class RefreshAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        event.project?.let { EntireService.getInstance(it).refresh() }
    }

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
