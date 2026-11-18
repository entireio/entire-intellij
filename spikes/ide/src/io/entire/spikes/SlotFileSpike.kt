package io.entire.spikes

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBLabel
import java.beans.PropertyChangeListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JComponent
import javax.swing.SwingConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Spike S2: can a panel be an editor tab that the IDE restores after a restart?
 *
 * The file is a slot with a fixed path. What it shows is kept beside it, so
 * the tab can show something else without becoming another file.
 */
object SlotFileSpike {
    const val PROTOCOL = "entire-spike-panel"
    const val KEY_PREFIX = "entire.spike.slot:"
    val created = AtomicInteger()
    val lookups = CopyOnWriteArrayList<String>()

    private fun slotPath(project: Project) = "/${project.locationHash}/detail/preview"

    suspend fun openAndRetarget(project: Project, report: Report) {
        val path = slotPath(project)
        PropertiesComponent.getInstance().setValue(KEY_PREFIX + path, "checkpoint:01HZXK3M5N7P9R2T4V6W8Y0A1B")
        val file = VirtualFileManager.getInstance().findFileByUrl("$PROTOCOL://$path")
        report.check("S2.fileResolvesByUrl", file is SpikePanelFile, "url ${file?.url}")
        if (file !is SpikePanelFile) return

        val editors = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFile(file, false) }
        report.check(
            "S2.opensAsPanelEditor",
            editors.size == 1 && editors[0] is SpikePanelEditor,
            editors.joinToString { it.javaClass.simpleName },
        )

        val firstTitle = withContext(Dispatchers.EDT) { EditorTabPresentationUtil.getEditorTabTitle(project, file) }
        withContext(Dispatchers.EDT) {
            file.title = "Second target"
            PropertiesComponent.getInstance().setValue(KEY_PREFIX + path, "checkpoint:01HZXK3M5N7P9R2T4V6W8Y0A2C")
            FileEditorManager.getInstance(project).updateFilePresentation(file)
        }
        val secondTitle = withContext(Dispatchers.EDT) { EditorTabPresentationUtil.getEditorTabTitle(project, file) }
        report.check(
            "S2.retargetsInPlace",
            secondTitle == "Second target" && created.get() == 1,
            "tab title went from \"$firstTitle\" to \"$secondTitle\", editors created ${created.get()}",
        )

        val again = VirtualFileManager.getInstance().findFileByUrl(file.url)
        report.check("S2.sameFileObjectEachLookup", again === file)

        withContext(Dispatchers.EDT) {
            val manager = FileEditorManagerEx.getInstanceEx(project)
            manager.currentWindow?.let { manager.createSplitter(SwingConstants.VERTICAL, it) }
        }
        delay(500)
        val inSplits = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).getAllEditors(file).size }
        report.check(
            "S2.twoSplitsGetTwoEditors",
            inSplits == 2 && created.get() == 2,
            "editors for the one file $inSplits, editors created ${created.get()}",
        )
        withContext(Dispatchers.EDT) { FileEditorManagerEx.getInstanceEx(project).unsplitAllWindow() }
    }

    suspend fun checkRestored(project: Project, report: Report) {
        var restored: VirtualFile? = null
        withTimeoutOrNull(20_000) {
            while (restored == null) {
                restored = withContext(Dispatchers.EDT) {
                    FileEditorManager.getInstance(project).openFiles.firstOrNull { it is SpikePanelFile }
                }
                if (restored == null) delay(250)
            }
        }
        val open = withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).openFiles.map { it.name } }
        report.check(
            "S2.tabRestoredAfterRestart",
            restored != null,
            "open files $open, editors created ${created.get()}, lookups during this start $lookups",
        )
        report.info("S2.targetKeptBesideTheSlot", PropertiesComponent.getInstance().getValue(KEY_PREFIX + slotPath(project)).orEmpty())
        restored?.let { file -> withContext(Dispatchers.EDT) { FileEditorManager.getInstance(project).closeFile(file) } }
    }
}

class SpikePanelFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    private val files = ConcurrentHashMap<String, SpikePanelFile>()

    override fun getProtocol(): String = SlotFileSpike.PROTOCOL

    override fun findFileByPath(path: String): VirtualFile? {
        val hash = path.removePrefix("/").substringBefore('/')
        val projectOpen = ProjectManager.getInstance().openProjects.any { it.locationHash == hash }
        SlotFileSpike.lookups += "$path (its project is in openProjects: $projectOpen)"
        // A slot nobody saved a target for is not a file.
        if (PropertiesComponent.getInstance().getValue(SlotFileSpike.KEY_PREFIX + path) == null) return null
        return files.computeIfAbsent(path) { SpikePanelFile(it, this) }
    }

    override fun refresh(asynchronous: Boolean) {}

    override fun refreshAndFindFileByPath(path: String): VirtualFile? = findFileByPath(path)
}

class SpikePanelFile(private val slotPath: String, private val owner: VirtualFileSystem) : LightVirtualFile("Entire") {
    @Volatile
    var title: String = "First target"

    override fun getFileSystem(): VirtualFileSystem = owner

    override fun getPath(): String = slotPath

    override fun getPresentableName(): String = title

    override fun isWritable(): Boolean = false
}

class SpikePanelEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is SpikePanelFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        SlotFileSpike.created.incrementAndGet()
        return SpikePanelEditor(file)
    }

    override fun getEditorTypeId(): String = "entire-spike-panel"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

class SpikePanelEditor(private val panelFile: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val label = JBLabel("A panel would draw here.", SwingConstants.CENTER)

    override fun getComponent(): JComponent = label

    override fun getPreferredFocusedComponent(): JComponent = label

    override fun getName(): String = "Entire"

    override fun getFile(): VirtualFile = panelFile

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {}
}
