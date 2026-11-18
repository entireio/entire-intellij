package io.entire.intellij

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.BrowserUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.StatusBar
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.testFramework.LightVirtualFile
import io.entire.app.ports.DiffFile
import io.entire.app.ports.Ui
import io.entire.app.setup.StepId
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the plugin shows and opens for the app module: notices, diffs, files, and links. */
class PluginUi(private val project: Project, private val browse: (String) -> Unit = { BrowserUtil.browse(it) }) : Ui {
    /** A notification of the plugin's group. A note carries the Entire icon, and a warning or an error the IDE's icon for its kind. */
    private fun notification(message: String, type: NotificationType): Notification {
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("Entire").createNotification(message, type)
        if (type == NotificationType.INFORMATION) notification.setIcon(EntireIcons.Entire)
        return notification
    }

    private fun onEdt(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) action()
        }
    }

    override fun info(message: String) = notification(message, NotificationType.INFORMATION).notify(project)

    /** An error says what went wrong, and the log has the CLI's own words, so each one opens the log. */
    override fun error(message: String) =
        notification(message, NotificationType.ERROR).addAction(showLogAction()).notify(project)

    /** A warning that reading more failed, with the way to try again and the log that says why. */
    fun warn(message: String, retry: () -> Unit) {
        notification(message, NotificationType.WARNING)
            .addAction(NotificationAction.createSimpleExpiring(EntireBundle.message("notification.retry")) { retry() })
            .addAction(showLogAction())
            .notify(project)
    }

    override fun offer(message: String, button: String, action: () -> Unit) {
        notification(message, NotificationType.INFORMATION)
            .addAction(NotificationAction.createSimpleExpiring(button) { action() })
            .notify(project)
    }

    override fun openSetup(step: StepId?) = EntireService.getInstance(project).openSetup(step)

    private fun showLogAction() = NotificationAction.createSimple(EntireBundle.message("notification.showLog")) { showLog() }

    /** Opens the IDE's log, where the plugin writes each command it runs and each failure. */
    fun showLog() = openLocal(PathManager.getLogDir().resolve("idea.log"))

    override suspend fun confirm(message: String, button: String): Boolean = withContext(Dispatchers.EDT) {
        MessageDialogBuilder.okCancel(EntireBundle.message("dialog.title"), message).yesText(button).ask(project)
    }

    override fun openUrl(url: String) = browse(url)

    override fun copy(value: String, note: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(value))
        onEdt { StatusBar.Info.set(note, project) }
    }

    override suspend fun <T> withProgress(title: String, work: suspend () -> T): T = withBackgroundProgress(project, title) { work() }

    override fun showDiff(title: String, files: List<DiffFile>) {
        val producers = files.map { file ->
            object : DiffRequestProducer {
                override fun getName(): String = file.path

                // Each side is read from git when its file is shown, and not before.
                override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
                    val (before, after) = runBlockingCancellable { file.before?.invoke() to file.after?.invoke() }
                    return SimpleDiffRequest(file.title, content(file, before), content(file, after), sideTitle(file, false), sideTitle(file, true))
                }
            }
        }
        onEdt { DiffManager.getInstance().showDiff(project, SimpleDiffRequestChain.fromProducers(producers), DiffDialogHints.DEFAULT) }
    }

    private fun content(file: DiffFile, bytes: ByteArray?) =
        if (bytes == null) {
            DiffContentFactory.getInstance().createEmpty()
        } else {
            val name = file.path.substringAfterLast('/')
            DiffContentFactory.getInstance().createFromBytes(project, bytes, FileTypeManager.getInstance().getFileTypeByFileName(name), name)
        }

    private fun sideTitle(file: DiffFile, after: Boolean): String =
        EntireBundle.message(if (after) "diff.after" else "diff.before", file.path)

    override fun openWorkingFile(path: Path) = openLocal(path)

    override fun openFile(path: Path) = openLocal(path)

    private fun openLocal(path: Path) {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        if (file == null) {
            error(EntireBundle.message("notification.file.unreadable", path.toString()))
            return
        }
        onEdt { FileEditorManager.getInstance(project).openFile(file, true) }
    }

    override fun openText(title: String, text: String) {
        val file = LightVirtualFile("$title.md", PlainTextFileType.INSTANCE, text)
        file.isWritable = false
        onEdt { FileEditorManager.getInstance(project).openFile(file, true) }
    }
}
