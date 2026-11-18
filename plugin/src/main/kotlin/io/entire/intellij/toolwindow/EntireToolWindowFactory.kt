package io.entire.intellij.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsListener
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import com.intellij.ui.content.ContentFactory
import git4idea.repo.GitRepositoryManager
import io.entire.intellij.EntireService
import java.nio.file.Files
import java.nio.file.Path

/**
 * The Entire tool window. The IDE calls this the first time the tool window
 * is shown, and each tab reads nothing until it is on screen itself.
 */
class EntireToolWindowFactory : ToolWindowFactory, DumbAware {
    /**
     * Entire records work in Git repositories, so a project without one has
     * no button for the tool window. This runs while the project opens, so it
     * only looks at the disk. [GitRootsListener] looks again when the VCS roots change.
     */
    override fun shouldBeAvailable(project: Project): Boolean = inGitRepository(project.basePath)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // One content with no name of its own: the title bar reads "Entire", and the tabs are a row under it.
        val content = ContentFactory.getInstance().createContent(EntireTabs(project, toolWindow.disposable), "", false)
        content.isCloseable = false
        toolWindow.contentManager.addContent(content)

        // The buttons that serve every tab are in the title bar, where they take no row from the lists.
        val actions = ActionManager.getInstance()
        toolWindow.setTitleActions(listOf(actions.getAction("Entire.Refresh"), actions.getAction("Entire.OpenRepoOnWeb"), actions.getAction("Entire.Settings")))
        toolWindow.setAdditionalGearActions(
            DefaultActionGroup(actions.getAction("Entire.Setup"), actions.getAction("Entire.ViewBrain"), actions.getAction("Entire.StopBrainGraph")),
        )

        val service = EntireService.getInstance(project)
        // The views keep themselves current only while the tool window is on screen.
        service.viewsShown(toolWindow.isVisible)
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: ToolWindowManager) = service.viewsShown(toolWindow.isVisible)
            },
        )
    }

    companion object {
        const val ID = "Entire"
    }
}

/** Whether a folder is in a Git repository: it or a folder above it has a `.git` folder, or a `.git` file as a worktree has. */
internal fun inGitRepository(folder: String?): Boolean {
    var current: Path? = folder?.let { Path.of(it) } ?: return false
    while (current != null) {
        if (Files.exists(current.resolve(".git"))) return true
        current = current.parent
    }
    return false
}

/**
 * Shows or hides the Entire tool window's button when the project's VCS roots
 * change, as when a folder of the project becomes a Git repository.
 */
class GitRootsListener(private val project: Project) : VcsListener {
    override fun directoryMappingChanged() {
        ApplicationManager.getApplication().executeOnPooledThread {
            if (project.isDisposed) return@executeOnPooledThread
            val available = inGitRepository(project.basePath) || GitRepositoryManager.getInstance(project).repositories.isNotEmpty()
            val manager = ToolWindowManager.getInstance(project)
            manager.invokeLater {
                val toolWindow = manager.getToolWindow(EntireToolWindowFactory.ID) ?: return@invokeLater
                if (toolWindow.isAvailable != available) toolWindow.isAvailable = available
            }
        }
    }
}
