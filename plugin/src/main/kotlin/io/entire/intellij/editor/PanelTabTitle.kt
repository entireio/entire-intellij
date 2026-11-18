package io.entire.intellij.editor

import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Names a panel's tab by what the panel shows. Every panel file has the same
 * file name, and the IDE tells tabs with one name apart by their paths, which
 * for a panel is a slot such as `detail/preview` and says nothing to a reader.
 */
class PanelTabTitle : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? = (file as? EntirePanelFile)?.title
}
