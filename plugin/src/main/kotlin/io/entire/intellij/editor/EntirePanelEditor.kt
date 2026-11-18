package io.entire.intellij.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** Opens a panel's file as a panel, and never as text. */
class EntirePanelEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean = file is EntirePanelFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = EntirePanelEditor(project, file as EntirePanelFile)

    override fun getEditorTypeId(): String = "entire-panel"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * One tab on a panel's file. A file open in two splits has two of these, and
 * both show the one panel behind the file.
 */
class EntirePanelEditor(private val project: Project, private val panelFile: EntirePanelFile) : UserDataHolderBase(), FileEditor {
    private val files = PanelFiles.getInstance(project)
    private val host = files.backing(panelFile)
    private val view: PanelView = PanelViewFactory.getInstance()
        ?.create(project, this, panelFile.page, { host?.receive(it) }, { host?.pageReset() })
        ?: FallbackPanelView()

    init {
        host?.attach(view)
    }

    override fun getComponent(): JComponent = view.component

    override fun getPreferredFocusedComponent(): JComponent = view.component

    override fun getName(): String = "Entire"

    override fun getFile(): VirtualFile = panelFile

    override fun setState(state: FileEditorState) {}

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = true

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun dispose() {
        files.release(panelFile, view)
        Disposer.dispose(view)
    }
}
