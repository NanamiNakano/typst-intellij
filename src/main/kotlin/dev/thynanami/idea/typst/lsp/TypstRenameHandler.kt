package dev.thynanami.idea.typst.lsp

import com.ibm.icu.lang.UCharacter
import com.ibm.icu.lang.UProperty
import com.intellij.codeInsight.highlighting.HighlightManager
import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateBuilderImpl
import com.intellij.codeInsight.template.TemplateEditingAdapter
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.TemplateState
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAndEdtWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.impl.FinishMarkAction
import com.intellij.openapi.command.impl.StartMarkAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.refactoring.rename.inplace.MyLookupExpression
import dev.thynanami.idea.typst.isTypstFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
import kotlin.time.Duration.Companion.seconds

// MyLookupExpression reads this standard platform template variable by name.
private const val renameVariable = "PrimaryVariable"
private val renameKeywords = setOf(
    "_", "none", "auto", "true", "false", "not", "and", "or", "let", "set", "show", "context",
    "if", "else", "for", "in", "while", "break", "continue", "return", "import", "include", "as",
)

class TypstRenameHandler : RenameHandler, DumbAware {
    override fun isAvailableOnDataContext(dataContext: DataContext): Boolean {
        val editor = CommonDataKeys.EDITOR.getData(dataContext) ?: return false
        val project = editor.project ?: return false
        val file = CommonDataKeys.VIRTUAL_FILE.getData(dataContext) ?: return false
        return renameClient(project, file) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null || file == null || editor.isDisposed || editor.caretModel.caretCount != 1) return
        if (TemplateManager.getInstance(project).getActiveTemplate(editor) != null || StartMarkAction.canStart(editor) != null) return
        val virtualFile = file.virtualFile ?: return
        val client = renameClient(project, virtualFile) ?: return
        val document = editor.document
        if (!document.isWritable) return
        val stamp = document.modificationStamp
        val offset = editor.caretModel.offset
        val identifier = client.getDocumentIdentifier(virtualFile)
        val position = getLsp4jPosition(document, offset)
        val prepared = runRenameRequest(project, editor, "Preparing rename") {
            val target = client.sendRequest {
                it.textDocumentService.prepareRename(PrepareRenameParams(identifier, position))
            } ?: return@runRenameRequest null
            val references = client.sendRequest {
                it.textDocumentService.references(ReferenceParams(identifier, position, ReferenceContext(true)))
            }.orEmpty()
            target to references
        } ?: return
        if (editor.isDisposed || document.modificationStamp != stamp || renameClient(project, virtualFile) !== client) return

        val preparedRange = prepared.first.map({ it }, { it.range }, { null })
            ?.let { getRangeInDocument(document, it) }
        if (preparedRange == null || preparedRange.isEmpty) {
            showRenameError(editor, "Tinymist cannot rename this target.")
            return
        }
        val placeholder = prepared.first.second?.placeholder
        val preparedText = document.getText(preparedRange)
        val labelLiteral = placeholder != null && preparedText == "<$placeholder>"
        val labelReference = preparedRange.startOffset > 0 && document.charsSequence[preparedRange.startOffset - 1] == '@'
        val path = placeholder != null && preparedText.length >= 2 && preparedText.startsWith('"') && preparedText.endsWith('"')
        if (!preparedRange.containsOffset(offset) && !(labelReference && offset == preparedRange.startOffset - 1)) {
            showRenameError(editor, "Tinymist cannot rename this target.")
            return
        }
        // Keep label delimiters and path quotes outside the editable name.
        val range = if (labelLiteral || path) TextRange(preparedRange.startOffset + 1, preparedRange.endOffset - 1) else preparedRange
        val kind = when {
            path -> RenameTargetKind.PATH
            labelLiteral || labelReference -> RenameTargetKind.LABEL
            else -> RenameTargetKind.IDENTIFIER
        }
        val originalNameText = document.getText(range)
        // Path placeholders are decoded by Tinymist; their source text may contain escapes.
        val oldName = placeholder ?: originalNameText
        if (oldName.isEmpty() || (!path && oldName != originalNameText)) {
            showRenameError(editor, "Tinymist returned an invalid rename range.")
            return
        }
        val occurrences = prepared.second.mapNotNull { location ->
            if (client.descriptor.findFileByUri(location.uri) != virtualFile) return@mapNotNull null
            getRangeInDocument(document, location.range)?.takeIf { document.getText(it) == originalNameText }
        }.plus(range).distinct().sortedBy { it.startOffset }
        if (occurrences.zipWithNext().any { (left, right) -> left.endOffset > right.startOffset }) return

        PsiDocumentManager.getInstance(project).commitDocument(document)
        TypstRenameSession(project, editor, file, client, range, occurrences, oldName, originalNameText, kind).start()
    }

    override fun invoke(project: Project, elements: Array<out PsiElement>, dataContext: DataContext?) {
        val editor = dataContext?.let { CommonDataKeys.EDITOR.getData(it) } ?: return
        invoke(project, editor, CommonDataKeys.PSI_FILE.getData(dataContext), dataContext)
    }
}

private fun renameClient(project: Project, file: VirtualFile): LspClient? {
    if (project.isDisposed || project.isDefault || !file.isValid || !file.isInLocalFileSystem || !file.isTypstFile()) return null
    val client = project.service<TinymistLanguageServer>().running() ?: return null
    // Bundled Tinymist supplies the target range through prepareRename.
    return client.takeIf { it.initializeResult?.capabilities?.renameProvider?.right?.prepareProvider == true }
}

private enum class RenameTargetKind { IDENTIFIER, LABEL, PATH }

private class TypstRenameSession(
    private val project: Project,
    private val editor: Editor,
    private val file: PsiFile,
    private val client: LspClient,
    private val origin: TextRange,
    private val occurrences: List<TextRange>,
    private val oldName: String,
    private val originalNameText: String,
    private val kind: RenameTargetKind,
) : TemplateEditingAdapter(), Disposable {
    private val document = editor.document
    private val originalText = document.text
    private val commandName = RefactoringBundle.message("renaming.command.name", oldName)
    private val highlights = mutableListOf<RangeHighlighter>()
    private var primaryHighlight: RangeHighlighter? = null
    private var mark: StartMarkAction? = null
    private var caret: RangeMarker? = null
    private var finishedRanges = emptyList<TextRange>()
    private var newName: String? = null
    private var pending = false
    private var finished = false
    private var initialized = false

    @Suppress("UnstableApiUsage") // An exact end offset is needed for Typst's flat PSI file.
    fun start() {
        val builder = TemplateBuilderImpl(file)
        val expression = MyLookupExpression(oldName, linkedSetOf(oldName), null, null, true, null)
        builder.replaceElement(file, origin, renameVariable, expression, true)
        occurrences.filter { it != origin }.forEachIndexed { index, range ->
            builder.replaceElement(file, range, "usage_$index", renameVariable, false)
        }
        builder.setEndVariableAt(origin.endOffset)
        WriteCommandAction.writeCommandAction(project).withName(commandName).run<RuntimeException> {
            mark = StartMarkAction.start(editor, project, commandName)
            try {
                val template = builder.buildInlineTemplate().apply {
                    setToIndent(false)
                    isToShortenLongNames = false
                    isToReformat = false
                }
                editor.selectionModel.removeSelection()
                editor.caretModel.moveToOffset(0)
                TemplateManager.getInstance(project).startTemplate(editor, template, this)
            } catch (error: Throwable) {
                // Startup is one write action: no user edits can interleave with the removed template slots.
                document.replaceString(0, document.textLength, originalText)
                finish()
                throw error
            }
        }
    }

    override fun currentVariableChanged(state: TemplateState, template: Template, oldIndex: Int, newIndex: Int) {
        if (initialized || newIndex < 0) return
        initialized = true
        Disposer.register(state, this)
        val manager = HighlightManager.getInstance(project)
        for (index in 0 until state.segmentsCount) {
            val name = template.getSegmentName(index)
            if (name != renameVariable && !name.startsWith("usage_")) continue
            val range = state.getSegmentRange(index)
            val color = if (name == renameVariable) EditorColors.WRITE_SEARCH_RESULT_ATTRIBUTES else EditorColors.SEARCH_RESULT_ATTRIBUTES
            manager.addOccurrenceHighlight(editor, range.startOffset, range.endOffset, color, 0, highlights)
            if (name == renameVariable) primaryHighlight = highlights.lastOrNull()
        }
        highlights.forEach {
            it.isGreedyToLeft = true
            it.isGreedyToRight = true
        }
    }

    override fun beforeTemplateFinished(state: TemplateState, template: Template) {
        newName = state.getVariableValue(renameVariable)?.text
        finishedRanges = (0 until state.segmentsCount).mapNotNull { index ->
            val name = template.getSegmentName(index)
            if (name != renameVariable && !name.startsWith("usage_")) return@mapNotNull null
            val range = state.getSegmentRange(index)
            if (name == renameVariable) caret = document.createRangeMarker(range.endOffset, range.endOffset)
            range
        }
    }

    override fun templateFinished(template: Template, brokenOff: Boolean) {
        try {
            // The template is inactive now; restoring these slots cannot propagate another linked edit.
            WriteCommandAction.writeCommandAction(project).withName(commandName).run<RuntimeException> {
                finishedRanges.sortedByDescending { it.startOffset }.forEach {
                    document.replaceString(it.startOffset, it.endOffset, originalNameText)
                }
            }
            val name = newName
            if (brokenOff || name.isNullOrBlank() || name == oldName || editor.isDisposed || project.isDisposed) return
            val error = when (kind) {
                RenameTargetKind.IDENTIFIER -> if (!isRenameIdentifier(name)) "Enter a valid Typst identifier that is not a keyword." else null
                RenameTargetKind.LABEL -> if (!isRenameLabel(name)) "Enter a valid Typst label name without leading or trailing ':' or '.'." else null
                RenameTargetKind.PATH -> if ('\u0000' in name) "Enter a valid file path." else null
            }
            if (error != null) {
                showRenameError(editor, error)
                return
            }
            if (!document.charsSequence.contentEquals(originalText)) {
                showRenameError(editor, "The file changed during rename. Please try again.")
                return
            }
            pending = true
            // Template completion may run inside a write action. Defer RPC until that action has finished.
            ApplicationManager.getApplication().invokeLater { commit(name) }
        } finally {
            clearHighlights()
            if (!pending) finish()
        }
    }

    override fun templateCancelled(template: Template) {
        if (UndoManager.getInstance(project).isUndoOrRedoInProgress) {
            // Undo restores the template edits itself.
            finish()
        } else {
            restoreCancelledTemplate()
        }
    }

    private fun restoreCancelledTemplate() {
        if (pending || finished) return
        pending = true
        val previewName = primaryHighlight?.takeIf { it.isValid }?.let {
            document.getText(TextRange(it.startOffset, it.endOffset))
        }
        // Editing outside the active slot also cancels a template. Keep live ranges until it is inactive.
        ApplicationManager.getApplication().invokeLater {
            try {
                if (!project.isDisposed && document.isWritable) {
                    WriteCommandAction.writeCommandAction(project).withName(commandName).run<RuntimeException> {
                        highlights.filter { it.isValid }.sortedByDescending { it.startOffset }.forEach {
                            val range = TextRange(it.startOffset, it.endOffset)
                            if (document.getText(range) == previewName) {
                                document.replaceString(range.startOffset, range.endOffset, originalNameText)
                            }
                        }
                    }
                }
            } finally {
                finish()
            }
        }
    }

    private fun commit(name: String) {
        try {
            if (project.isDisposed || editor.isDisposed) return
            if (renameClient(project, file.virtualFile) !== client || !document.charsSequence.contentEquals(originalText)) {
                showRenameError(editor, "The file or language server changed. Please rename again.")
                return
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val stamp = document.modificationStamp
            val psiStamp = PsiModificationTracker.getInstance(project).modificationCount
            val params = RenameParams(client.getDocumentIdentifier(file.virtualFile), getLsp4jPosition(document, origin.startOffset), name)
            val applied = runRenameRequest(project, editor, commandName) {
                val edit = client.sendRequest { it.textDocumentService.rename(params) } ?: return@runRenameRequest null
                readAndEdtWriteAction {
                    if (document.modificationStamp != stamp || PsiModificationTracker.getInstance(project).modificationCount != psiStamp ||
                        renameClient(project, file.virtualFile) !== client
                    ) throw IllegalStateException("The project changed during rename. Please try again.")
                    val action = TypstRenameEdit(client, edit, commandName)
                    if (!action.prepare()) throw IllegalStateException("The rename edits are no longer available.")
                    writeAction {
                        var applied = false
                        CommandProcessor.getInstance().executeCommand(project, { applied = action.apply(file.virtualFile) }, commandName, null)
                        applied
                    }
                }
            }
            if (applied == true && !editor.isDisposed) caret?.takeIf { it.isValid }?.let { editor.caretModel.moveToOffset(it.endOffset) }
        } finally {
            finish()
        }
    }

    private fun clearHighlights() {
        if (highlights.isEmpty()) return
        if (project.isDisposed || editor.isDisposed) {
            highlights.forEach { it.dispose() }
        } else {
            val manager = HighlightManager.getInstance(project)
            highlights.forEach { manager.removeSegmentHighlighter(editor, it) }
        }
        highlights.clear()
        primaryHighlight = null
    }

    private fun finish() {
        if (finished) return
        finished = true
        clearHighlights()
        caret?.dispose()
        caret = null
        val started = mark
        mark = null
        if (!project.isDisposed) FinishMarkAction.finish(project, editor, started)
    }

    override fun dispose() {
        if (!pending && !finished) restoreCancelledTemplate()
    }
}

private fun <T> runRenameRequest(project: Project, editor: Editor, title: String, request: suspend () -> T?): T? = try {
    runWithModalProgressBlocking(project, title) { withTimeout(30.seconds) { request() } }
} catch (_: TimeoutCancellationException) {
    showRenameError(editor, "Tinymist did not respond to the rename request in time.")
    null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (cancelled: ProcessCanceledException) {
    throw cancelled
} catch (error: ResponseErrorException) {
    showRenameError(editor, error.responseError?.message ?: "Tinymist could not rename this target.")
    null
} catch (error: Exception) {
    logger<TypstRenameHandler>().warn("Could not rename Typst symbol", error)
    showRenameError(editor, error.message ?: "Could not rename this symbol.")
    null
}

private fun showRenameError(editor: Editor, message: String) {
    if (!editor.isDisposed && editor.project?.isDisposed == false) HintManager.getInstance().showErrorHint(editor, message)
}

private fun isRenameIdentifier(name: String): Boolean {
    if (name.isEmpty() || name in renameKeywords) return false
    // Match typst-syntax's Unicode XID rules, including underscores and continuing hyphens.
    var offset = 0
    while (offset < name.length) {
        val character = name.codePointAt(offset)
        val property = if (offset == 0) UProperty.XID_START else UProperty.XID_CONTINUE
        if (character != '_'.code && !(offset > 0 && character == '-'.code) && !UCharacter.hasBinaryProperty(character, property)) return false
        offset += Character.charCount(character)
    }
    return true
}

// Typst permits these characters in labels, but @references trim trailing ':' and '.'.
private fun isRenameLabel(name: String): Boolean =
    name.isNotEmpty() && name.first() !in ":." && name.last() !in ":." && name.codePoints().allMatch {
        it == '_'.code || it == '-'.code || it == ':'.code || it == '.'.code || UCharacter.hasBinaryProperty(it, UProperty.XID_CONTINUE)
    }
