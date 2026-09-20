package dev.thynanami.idea.typst.lsp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import dev.thynanami.idea.typst.lsp.outline.TypstOutlineModel
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

private val LOG = logger<TypstLspClient>()

class TypstLspClient(
    private val project: Project,
    serverNotificationsHandler: LspServerNotificationsHandler,
    private val descriptor: TinymistLanguageServerDescriptor,
) : Lsp4jClient(serverNotificationsHandler) {
    @JsonNotification("tinymist/documentOutline")
    fun handleDocumentOutline(outline: DocumentOutline) {
        if (!project.isDisposed) project.service<TypstOutlineModel>().update(outline)
    }

    @JsonNotification("tinymist/preview/dispose")
    fun handlePreviewDisposed(disposed: PreviewDisposed) {
        descriptor.previewDisposed(disposed.taskId)
    }

    @JsonNotification("tinymist/preview/scrollSource")
    fun handleScrollSource(jump: SourceJump) {
        LOG.info("Preview asked to scroll to " + jump.filepath + " at " + jump.start)

        val position = jump.start?.takeIf { it.size >= 2 } ?: return
        val file = LocalFileSystem.getInstance().findFileByPath(jump.filepath)
        if (file == null) {
            LOG.warn("Cannot scroll to unknown file " + jump.filepath)
            return
        }

        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater

            val editors = FileEditorManager.getInstance(project).openFile(file, true)
            val editor = editors.firstNotNullOfOrNull(::textEditorOf)
            if (editor == null) {
                LOG.warn("No text editor to scroll among " + editors.map { it.javaClass.name })
                return@invokeLater
            }

            editor.caretModel.moveToLogicalPosition(LogicalPosition(position[0], position[1]))
            editor.scrollingModel.scrollToCaret(ScrollType.CENTER)
            IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
        }
    }

    private fun textEditorOf(fileEditor: FileEditor): Editor? = when (fileEditor) {
        is TextEditorWithPreview -> fileEditor.textEditor.editor
        is TextEditor -> fileEditor.editor
        else -> null
    }
}
