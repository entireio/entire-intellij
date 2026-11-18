package io.entire.intellij.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import io.entire.intellij.EntireBundle
import io.entire.intellij.EntireService
import javax.swing.JLabel

/**
 * Opens the repository's page on entire.com. Only a repository whose origin
 * remote uses the entire:// scheme has one, so the action is absent without
 * such a repository. With several, a list asks which.
 */
class OpenRepoOnWebAction : DumbAwareAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = EntireService.getInstance(project)
        val repos = service.chrome.value.webRepos
        val only = repos.singleOrNull()
        if (only != null) {
            service.openRepoOnWeb(only.first)
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(repos)
            .setTitle(EntireBundle.message("web.choose"))
            .setRenderer { _, repo: Pair<String, String>, _, _, _ -> JLabel(repo.second) }
            .setItemChosenCallback { service.openRepoOnWeb(it.first) }
            .createPopup()
            .showCenteredInCurrentWindow(project)
    }

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabledAndVisible = project != null && EntireService.getInstance(project).chrome.value.webRepos.isNotEmpty()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
