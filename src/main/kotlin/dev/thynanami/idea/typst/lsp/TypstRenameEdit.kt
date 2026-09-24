package dev.thynanami.idea.typst.lsp

import com.intellij.codeInsight.FileModificationService
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.customization.LspIntentionAction
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.UUID

/** Applies Tinymist's text edits and optional trailing file rename in the caller's undo command. */
internal class TypstRenameEdit(
    private val client: LspClient,
    private val edit: WorkspaceEdit,
    private val title: String,
) {
    private val documents = mutableListOf<RenameDocument>()
    private var action: LspIntentionAction? = null
    private var move: RenameFileMove? = null
    private var prepared = false

    @RequiresReadLock
    fun prepare(): Boolean {
        documents.clear()
        action = null
        move = null
        prepared = false
        require(edit.changes.isNullOrEmpty() || edit.documentChanges.isNullOrEmpty()) {
            "Tinymist returned two conflicting forms of rename edits."
        }
        val textEdits = linkedMapOf<String, List<TextEdit>>()
        edit.changes?.let { textEdits.putAll(it) }
        edit.documentChanges.orEmpty().forEachIndexed { index, change ->
            if (change.isLeft) {
                val documentEdit = change.left
                require(textEdits.put(documentEdit.textDocument.uri, documentEdit.edits) == null) {
                    "Tinymist returned repeated edits for the same file."
                }
            } else {
                val rename = change.right as? RenameFile
                    ?: error("Tinymist returned an unsupported file operation.")
                require(move == null && index == edit.documentChanges.lastIndex && rename.options == null) {
                    "Tinymist returned an unsupported file rename sequence."
                }
                move = prepareMove(rename)
            }
        }
        for ((uri, edits) in textEdits) {
            localRenamePath(uri)
            val file = client.descriptor.findFileByUri(uri) ?: return false
            if (!file.isValid || !file.isInLocalFileSystem || file.isDirectory) return false
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
            val ranges = edits.map { textEdit ->
                val range = getRangeInDocument(document, textEdit.range) ?: return false
                range to StringUtil.convertLineSeparators(textEdit.newText)
            }.sortedBy { it.first.startOffset }
            require(ranges.zipWithNext().none { (left, right) -> left.first.endOffset > right.first.startOffset }) {
                "Tinymist returned overlapping rename edits."
            }
            val original = document.text
            val expected = StringBuilder(original)
            ranges.asReversed().forEach { (range, replacement) -> expected.replace(range.startOffset, range.endOffset, replacement) }
            documents += RenameDocument(file, file.url, document, document.modificationStamp, original, expected.toString())
        }
        if (textEdits.isNotEmpty()) {
            val textOnly = WorkspaceEdit().apply {
                changes = edit.changes
                documentChanges = edit.documentChanges?.filter { it.isLeft }
                changeAnnotations = edit.changeAnnotations
            }
            action = LspIntentionAction(client, CodeAction(title).apply { this.edit = textOnly })
            if (action?.isAvailable() != true) return false
        }
        prepared = action != null || move != null
        return prepared
    }

    @RequiresEdt
    @RequiresWriteLock
    fun apply(contextFile: VirtualFile?): Boolean {
        check(prepared) { "Rename edits were not prepared." }
        validate()
        val writable = documents.map { it.file } + move?.let { listOf(it.file, it.parent, it.ancestor) }.orEmpty()
        if (!FileModificationService.getInstance().prepareVirtualFilesForWrite(client.project, writable.distinct())) return false
        validate()
        if (documents.any { !it.document.isWritable } || writable.any { !it.isWritable }) return false

        val createdDirectories = mutableListOf<VirtualFile>()
        try {
            // Tinymist's text edits still refer to the original URI, so apply them before moving the file.
            action?.invoke(contextFile)
            check(documents.all { it.document.charsSequence.contentEquals(it.expected) }) { "The rename edits could not be applied." }
            move?.let { plan ->
                var parent = plan.ancestor
                for (part in plan.ancestor.toNioPath().relativize(plan.destination.parent)) {
                    val name = part.toString()
                    if (name.isEmpty()) continue
                    parent = parent.findChild(name) ?: parent.createChildDirectory(this, name).also(createdDirectories::add)
                    check(parent.isDirectory) { "The rename destination is not a directory." }
                }
                moveFile(plan.file, parent, plan.destination.fileName.toString())
            }
            return true
        } catch (failure: Throwable) {
            // VFS failures can occur after a successful text edit; restore both while the same command is open.
            move?.let { plan ->
                runCatching { moveFile(plan.file, plan.parent, plan.name) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            documents.forEach { snapshot ->
                runCatching {
                    snapshot.document.replaceString(0, snapshot.document.textLength, snapshot.original)
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            createdDirectories.asReversed().forEach { directory ->
                runCatching {
                    if (directory.isValid && directory.children.isEmpty()) directory.delete(this)
                }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        } finally {
            prepared = false
        }
    }

    private fun prepareMove(rename: RenameFile): RenameFileMove {
        val oldPath = localRenamePath(rename.oldUri)
        val destination = localRenamePath(rename.newUri)
        val file = client.descriptor.findFileByUri(rename.oldUri)
            ?: error("The file to rename no longer exists.")
        require(file.isValid && file.isInLocalFileSystem && !file.isDirectory && file.toNioPath().normalize() == oldPath) {
            "Tinymist can only rename existing local files."
        }
        val parent = file.parent ?: error("The file to rename has no parent directory.")
        val destinationParent = destination.parent ?: error("The rename destination has no parent directory.")
        require(file.fileSystem.isValidName(destination.fileName.toString())) { "The new file name is not valid." }
        var ancestorPath = destinationParent
        while (!Files.exists(ancestorPath, LinkOption.NOFOLLOW_LINKS)) {
            ancestorPath = ancestorPath.parent ?: error("The rename destination is unavailable.")
        }
        val ancestor = LocalFileSystem.getInstance().findFileByNioFile(ancestorPath)
            ?: error("Refresh the rename destination and try again.")
        require(ancestor.isDirectory) { "The rename destination is not a directory." }
        destinationParent.forEach { require(file.fileSystem.isValidName(it.toString())) { "The new file path is not valid." } }
        return RenameFileMove(file, parent, file.name, destination, ancestor, ancestorPath).also(::validateMove)
    }

    private fun validate() {
        check(!client.project.isDisposed && documents.all {
            it.file.isValid && it.file.url == it.url && it.document.modificationStamp == it.stamp
        }) {
            "The files changed while preparing rename. Please try again."
        }
        move?.let(::validateMove)
    }

    private fun validateMove(plan: RenameFileMove) {
        check(plan.file.isValid && plan.file.parent == plan.parent && plan.file.name == plan.name &&
            plan.ancestor.isValid && plan.ancestor.toNioPath() == plan.ancestorPath
        ) {
            "The file or destination changed while preparing rename. Please try again."
        }
        var parentPath = plan.destination.parent
        while (parentPath != plan.ancestorPath) {
            require(!Files.exists(parentPath, LinkOption.NOFOLLOW_LINKS) || Files.isDirectory(parentPath)) {
                "The rename destination is not a directory."
            }
            parentPath = parentPath.parent ?: error("The rename destination changed.")
        }
        val existing = LocalFileSystem.getInstance().findFileByNioFile(plan.destination)
        require(!Files.exists(plan.destination, LinkOption.NOFOLLOW_LINKS) || existing == plan.file) {
            "A file already exists at the rename destination."
        }
    }

    private fun moveFile(file: VirtualFile, parent: VirtualFile, name: String) {
        check(file.isValid && parent.isValid) { "The file or its parent is no longer available." }
        require(parent.findChild(name).let { it == null || it == file }) { "A file already exists at the rename destination." }
        if (file.parent != parent) {
            if (parent.findChild(file.name) != null) {
                // Use a temporary name only when the intermediate move would collide with an unrelated file.
                var temporary: String
                do {
                    temporary = ".typst-rename-${UUID.randomUUID()}"
                } while (file.parent.findChild(temporary) != null || parent.findChild(temporary) != null)
                file.rename(this, temporary)
            }
            file.move(this, parent)
        }
        if (file.name != name) file.rename(this, name)
    }
}

private class RenameDocument(
    val file: VirtualFile,
    val url: String,
    val document: Document,
    val stamp: Long,
    val original: String,
    val expected: String,
)

private class RenameFileMove(
    val file: VirtualFile,
    val parent: VirtualFile,
    val name: String,
    val destination: Path,
    val ancestor: VirtualFile,
    val ancestorPath: Path,
)

private fun localRenamePath(uri: String): Path {
    val parsed = URI(uri)
    require(parsed.scheme == "file") { "Tinymist can only rename local files." }
    return Path.of(parsed).toAbsolutePath().normalize()
}
