package dev.thynanami.idea.typst.editor

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.TabOutScopesTracker
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorModificationUtilEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import dev.thynanami.idea.typst.TypstFileTypeBase
import org.jetbrains.plugins.textmate.language.TextMateStandardTokenType

private val replacingSelectionKey = Key.create<Boolean>("typst.replacingSelection")
private val standardTokenScope = Regex("\\b(comment|string|regex|meta\\.embedded)\\b")
private const val wordSeparators = "`~!@#$%^&*()-=+[{]}\\|;:'\",.<>/?"

class TypstTypedHandler : TypedHandlerDelegate() {
    override fun beforeSelectionRemoved(c: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file.fileType !is TypstFileTypeBase) return Result.CONTINUE
        val caret = editor.caretModel.currentCaret
        caret.putUserData(replacingSelectionKey, caret.hasSelection())
        val selected = caret.selectedText ?: return Result.CONTINUE
        // Suppress the platform's generic surrounding pairs and quote conversion for Typst.
        if (!CodeInsightSettings.getInstance().SURROUND_SELECTION_ON_QUOTE_TYPED ||
            selected.all { it == ' ' || it == '\t' || it == '\n' } ||
            c.isQuote() && selected.length == 1 && selected[0].isQuote()
        ) return Result.DEFAULT
        val pair = editor.typstPreferences(file, caret.selectionStart)
            .firstNotNullOfOrNull { it.surroundingPairs }?.firstOrNull { it.left == c.toString() }
            ?: return Result.DEFAULT
        val reversed = caret.offset == caret.selectionStart
        val start = caret.selectionStart + pair.left.length
        EditorModificationUtilEx.insertStringAtCaret(editor, pair.left + selected + pair.right)
        val end = start + selected.length
        caret.setSelection(if (reversed) end else start, if (reversed) start else end)
        caret.moveToOffset(if (reversed) start else end)
        caret.putUserData(replacingSelectionKey, null)
        return Result.STOP
    }

    override fun beforeCharTyped(
        c: Char, project: Project, editor: Editor, file: PsiFile, fileType: FileType,
    ): Result {
        if (fileType !is TypstFileTypeBase) return Result.CONTINUE
        val caret = editor.caretModel.currentCaret
        val replacingSelection = caret.getUserData(replacingSelectionKey) == true
        caret.putUserData(replacingSelectionKey, null)
        val state = caret.typstAutoClosingPairs()
        val tracked = state?.takeAtCaret()
        val offset = caret.offset
        val text = editor.document.charsSequence
        if (tracked != null) {
            if (!replacingSelection && c == tracked.closeChar && typstPairEnabled(c) &&
                !(c.isQuote() && text.getOrNull(offset - 1) == '\\')
            ) {
                TabOutScopesTracker.getInstance().removeScopeEndingAt(editor, offset)
                caret.moveToOffset(offset + 1)
                tracked.dispose()
                return Result.STOP
            }
            state.restore(tracked)
        }
        val preferences = editor.typstPreferences(file, offset)
        val pairs = preferences.firstNotNullOfOrNull { it.smartTypingPairs } ?: return Result.CONTINUE
        if (pairs.none { it.left.singleOrNull() == c || it.right.singleOrNull() == c }) return Result.CONTINUE
        val pair = pairs.firstOrNull { it.left.singleOrNull() == c && it.right.length == 1 }
        val lineStart = editor.document.getLineStartOffset(editor.document.getLineNumber(offset))
        val scope = editor.typstScopeAt(maxOf(lineStart, offset - 1)) ?: editor.typstScopeAt(offset - 1)
        val token = scope.typstScopeNames()
            .firstNotNullOfOrNull { standardTokenScope.find(it)?.value }
        val tokenType = when (token) {
            "string" -> TextMateStandardTokenType.STRING
            "comment" -> TextMateStandardTokenType.COMMENT
            else -> null
        }
        val next = text.getOrNull(offset)
        val previous = text.getOrNull(offset - 1)
        val autoCloseBefore = preferences.firstNotNullOfOrNull { it.autoCloseBefore }.orEmpty()
        val insertPair = pair != null && !replacingSelection && typstPairEnabled(c) &&
            (tokenType == null || !pair.notIn(tokenType)) &&
            (next == null || next == '\n' || next in autoCloseBefore) &&
            !(c == '"' && previous != null && !previous.isWhitespace() && previous !in wordSeparators)
        // Owning this edit also prevents native bracket handling from inserting a second closer.
        EditorModificationUtilEx.insertStringAtCaret(editor, c.toString() + if (insertPair) pair.right else "", false, 1)
        if (insertPair) {
            val inside = caret.offset
            caret.typstAutoClosingPairs(true)!!.add(inside - 1, inside, c, pair.right.single())
            TabOutScopesTracker.getInstance().registerEmptyScopeAtCaret(editor)
        }
        return Result.STOP
    }

    override fun isImmediatePaintingEnabled(editor: Editor, c: Char, context: DataContext): Boolean =
        c !in "()[]{}\"$*_`" ||
            FileDocumentManager.getInstance().getFile(editor.document)?.fileType !is TypstFileTypeBase
}

private fun Char.isQuote() = this == '"' || this == '\'' || this == '`'

internal fun typstPairEnabled(c: Char): Boolean = CodeInsightSettings.getInstance().let {
    if (c.isQuote()) it.AUTOINSERT_PAIR_QUOTE else it.AUTOINSERT_PAIR_BRACKET
}
