package io.entire.intellij.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.DeprecatedVirtualFileSystem
import com.intellij.openapi.vfs.NonPhysicalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.testFramework.LightVirtualFile
import io.entire.app.panel.DetailPanelController
import io.entire.app.panel.PanelSink
import io.entire.app.panel.TrailPanelController
import io.entire.app.panel.TrailPanelSink
import io.entire.core.json.parseJson
import io.entire.core.json.stringify
import io.entire.core.protocol.DetailTarget
import io.entire.core.protocol.TrailTarget
import io.entire.core.protocol.readDetailTarget
import io.entire.core.protocol.readTrailTarget
import io.entire.intellij.EntireService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val PROTOCOL = "entire-panel"
private const val PREVIEW = "preview"

/** The page of a checkpoint or a session. */
const val PAGE_DETAIL = "detail"

/** The page of a trail. */
const val PAGE_TRAIL = "trail"

/** How many tabs of one kind are remembered. The oldest is forgotten first. */
private const val MAX_SLOTS = 30

/**
 * A panel's file: a slot with a fixed address. What the slot shows is kept
 * beside it, because a tab moves on to another checkpoint or trail without
 * becoming another file. `page` says which of the two pages the slot holds.
 */
class EntirePanelFile(val page: String, val slot: String, private val address: String, private val owner: VirtualFileSystem) :
    LightVirtualFile("Entire", PlainTextFileType.INSTANCE, "") {
    @Volatile
    var title: String = "Entire"

    override fun getFileSystem(): VirtualFileSystem = owner

    override fun getPath(): String = address

    override fun getPresentableName(): String = title

    override fun isWritable(): Boolean = false
}

/**
 * Resolves the address of a panel tab that the IDE saved, when it reopens a
 * project. The address names the project, the page, and the slot, and the
 * project's [PanelFiles] says whether the slot still has something to show.
 */
class EntirePanelFileSystem : DeprecatedVirtualFileSystem(), NonPhysicalFileSystem {
    override fun getProtocol(): String = PROTOCOL

    override fun findFileByPath(path: String): VirtualFile? {
        val parts = path.trim('/').split('/')
        if (parts.size != 3 || (parts[1] != PAGE_DETAIL && parts[1] != PAGE_TRAIL)) return null
        // The IDE asks while the project opens, and the project is among the open ones by then.
        val project = ProjectManager.getInstance().openProjects.firstOrNull { it.locationHash == parts[0] } ?: return null
        return PanelFiles.getInstance(project).restored(parts[1], parts[2])
    }

    override fun refresh(asynchronous: Boolean) {}

    override fun refreshAndFindFileByPath(path: String): VirtualFile? = findFileByPath(path)

    companion object {
        fun getInstance(): EntirePanelFileSystem = VirtualFileManager.getInstance().getFileSystem(PROTOCOL) as EntirePanelFileSystem
    }
}

/** What stands behind a panel's file, as far as a tab on the file needs it. */
interface PanelBacking {
    /** A message from the page, as it arrived. */
    fun receive(message: JsonElement?)

    /** The page was loaded again and has to be told everything anew. */
    fun pageReset()

    fun attach(view: PanelView)

    /** Returns true when no tab shows the file any more. */
    fun detach(view: PanelView): Boolean

    fun dispose()
}

/** The tabs that show one panel, and what each of them is told. */
private class Tabs(private val project: Project, private val file: EntirePanelFile) {
    private val views = CopyOnWriteArrayList<PanelView>()

    fun attach(view: PanelView) {
        views.add(view)
    }

    fun detach(view: PanelView): Boolean {
        views.remove(view)
        return views.isEmpty()
    }

    fun post(message: JsonObject) = views.forEach { it.post(message) }

    fun readText(onText: (String) -> Unit) = views.firstOrNull()?.readText(onText) ?: onText("")

    fun setTitle(title: String) {
        file.title = title
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) FileEditorManager.getInstance(project).updateFilePresentation(file)
        }
    }
}

/** The panel of a checkpoint or a session behind one file: the controller, and the views of every tab that shows the file. */
class PanelHost(private val project: Project, private val file: EntirePanelFile, target: DetailTarget) : PanelSink, PanelBacking {
    private val tabs = Tabs(project, file)

    /** The tool window tab the panel was last opened from. A tab the IDE brought back is followed by the Checkpoints tab. */
    @Volatile
    var origin: String = EntireService.ORIGIN_CHECKPOINTS
    private val controller: DetailPanelController = EntireService.getInstance(project).newDetailController(this, target) { origin }

    init {
        controller.start()
    }

    fun show(target: DetailTarget) = controller.show(target)

    override fun receive(message: JsonElement?) = controller.receive(message)

    override fun pageReset() = controller.pageReset()

    suspend fun snapshot() = controller.snapshot()

    fun readText(onText: (String) -> Unit) = tabs.readText(onText)

    override fun attach(view: PanelView) = tabs.attach(view)

    override fun detach(view: PanelView): Boolean = tabs.detach(view)

    override fun dispose() = controller.dispose()

    override fun post(message: JsonObject) = tabs.post(message)

    override fun setTitle(title: String) = tabs.setTitle(title)

    override fun showing(target: DetailTarget) {
        PanelFiles.getInstance(project).remember(file.slot, target)
    }
}

/** The panel of a trail behind one file. */
class TrailHost(private val project: Project, private val file: EntirePanelFile, target: TrailTarget) : TrailPanelSink, PanelBacking {
    private val tabs = Tabs(project, file)
    private val controller: TrailPanelController = EntireService.getInstance(project).newTrailController(this, target)

    init {
        controller.start()
    }

    fun show(target: TrailTarget) = controller.show(target)

    /** Reads the trail again, for a change of the setting that allows trails. */
    fun refresh() = controller.refresh()

    override fun receive(message: JsonElement?) = controller.receive(message)

    override fun pageReset() = controller.pageReset()

    suspend fun snapshot() = controller.snapshot()

    fun readText(onText: (String) -> Unit) = tabs.readText(onText)

    override fun attach(view: PanelView) = tabs.attach(view)

    override fun detach(view: PanelView): Boolean = tabs.detach(view)

    override fun dispose() = controller.dispose()

    override fun post(message: JsonObject) = tabs.post(message)

    override fun setTitle(title: String) = tabs.setTitle(title)

    override fun showing(target: TrailTarget) {
        PanelFiles.getInstance(project).rememberTrail(file.slot, target)
    }
}

/**
 * The project's panel tabs: their files, what each one shows, and the panel
 * behind each file. What each tab shows is saved with the workspace, so the
 * IDE can bring the tabs back.
 *
 * Trails have slots and a reusable tab of their own. A checkpoint opened from
 * a trail's timeline therefore lands beside the trail, and not in its place.
 */
@Service(Service.Level.PROJECT)
@State(name = "EntirePanels", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class PanelFiles(private val project: Project) : PersistentStateComponent<PanelFiles.Saved> {
    /** Slot to target, as JSON text, oldest first. */
    class Saved {
        @JvmField
        var slots: MutableMap<String, String> = LinkedHashMap()

        @JvmField
        var nextTab: Int = 1

        @JvmField
        var trailSlots: MutableMap<String, String> = LinkedHashMap()

        @JvmField
        var nextTrailTab: Int = 1
    }

    private var saved = Saved()
    private val files = ConcurrentHashMap<String, EntirePanelFile>()
    private val hosts = ConcurrentHashMap<String, PanelHost>()
    private val trailHosts = ConcurrentHashMap<String, TrailHost>()

    override fun getState(): Saved = saved

    override fun loadState(state: Saved) {
        saved = state
    }

    private fun file(page: String, slot: String): EntirePanelFile =
        files.computeIfAbsent("$page/$slot") { EntirePanelFile(page, slot, "/${project.locationHash}/$page/$slot", EntirePanelFileSystem.getInstance()) }

    @Synchronized
    fun remember(slot: String, target: DetailTarget) = keep(saved.slots, slot, stringify(target.toJson()))

    @Synchronized
    fun rememberTrail(slot: String, target: TrailTarget) = keep(saved.trailSlots, slot, stringify(target.toJson()))

    private fun keep(slots: MutableMap<String, String>, slot: String, target: String) {
        slots.remove(slot)
        slots[slot] = target
        while (slots.size > MAX_SLOTS) slots.remove(slots.keys.first())
    }

    /** What a slot shows, when something valid is saved for it. A target that fails the check is treated as absent. */
    @Synchronized
    fun target(slot: String): DetailTarget? = saved.slots[slot]?.let { readDetailTarget(parseJson(it)) }

    @Synchronized
    fun trailTarget(slot: String): TrailTarget? = saved.trailSlots[slot]?.let { readTrailTarget(parseJson(it)) }

    /** The file of a slot the IDE saved, or null when the slot has nothing to show. */
    fun restored(page: String, slot: String): VirtualFile? {
        val known = if (page == PAGE_TRAIL) trailTarget(slot) != null else target(slot) != null
        return if (known) file(page, slot) else null
    }

    /** Opens a target: in the one tab that each click reuses, or in a tab of its own. */
    fun open(target: DetailTarget, newTab: Boolean, focus: Boolean, origin: String) {
        val slot = if (newTab) nextTabSlot() else PREVIEW
        remember(slot, target)
        val file = file(PAGE_DETAIL, slot)
        val existing = hosts[slot]
        if (existing != null) {
            existing.origin = origin
            existing.show(target)
        } else {
            host(file)?.origin = origin
        }
        show(file, focus)
    }

    /** Opens a trail: in the one tab that each click on a trail reuses, or in a tab of its own. */
    fun openTrail(target: TrailTarget, newTab: Boolean, focus: Boolean) {
        val slot = if (newTab) nextTrailTabSlot() else PREVIEW
        rememberTrail(slot, target)
        val file = file(PAGE_TRAIL, slot)
        val existing = trailHosts[slot]
        if (existing != null) existing.show(target) else trailHost(file)
        show(file, focus)
    }

    private fun show(file: EntirePanelFile, focus: Boolean) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) FileEditorManager.getInstance(project).openFile(file, focus)
        }
    }

    @Synchronized
    private fun nextTabSlot(): String = "tab-${saved.nextTab++}"

    @Synchronized
    private fun nextTrailTabSlot(): String = "tab-${saved.nextTrailTab++}"

    /** The panel behind a file of a checkpoint or a session. The first tab to ask starts it. */
    fun host(file: EntirePanelFile): PanelHost? {
        val target = target(file.slot) ?: return null
        return hosts.computeIfAbsent(file.slot) { PanelHost(project, file, target) }
    }

    /** The panel behind a file of a trail. The first tab to ask starts it. */
    fun trailHost(file: EntirePanelFile): TrailHost? {
        val target = trailTarget(file.slot) ?: return null
        return trailHosts.computeIfAbsent(file.slot) { TrailHost(project, file, target) }
    }

    /** The panel behind a file, whichever page it holds. */
    fun backing(file: EntirePanelFile): PanelBacking? = if (file.page == PAGE_TRAIL) trailHost(file) else host(file)

    /** A tab closed. The panel stops when it was the last one on its file. */
    fun release(file: EntirePanelFile, view: PanelView) {
        val running = if (file.page == PAGE_TRAIL) trailHosts else hosts
        val host = running[file.slot] ?: return
        if (host.detach(view)) {
            running.remove(file.slot)
            host.dispose()
        }
    }

    /** The panel on the tab that reuses itself, for a check that reads what it shows. */
    fun previewHost(): PanelHost? = hosts[PREVIEW]

    /** The trail panel on the tab that reuses itself. */
    fun trailPreviewHost(): TrailHost? = trailHosts[PREVIEW]

    /** Every open trail panel. */
    fun openTrails(): List<TrailHost> = trailHosts.values.toList()

    companion object {
        fun getInstance(project: Project): PanelFiles = project.service()
    }
}
