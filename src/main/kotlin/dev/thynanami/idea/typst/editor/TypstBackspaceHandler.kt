package dev.thynanami.idea.typst.editor

import com.intellij.codeInsight.editorActions.BackspaceHandlerDelegate
import com.intellij.codeInsight.editorActions.TabOutScopesTracker
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import dev.thynanami.idea.typst.TypstFileTypeBase

class TypstBackspaceHandler : BackspaceHandlerDelegate() {
    override fun beforeCharDeleted(c: Char, file: PsiFile, editor: Editor) {
        if (file.fileType !is TypstFileTypeBase) return
        val caret = editor.caretModel.currentCaret
        val state = caret.typstAutoClosingPairs() ?: return
        state.pendingDeletion?.dispose()
        state.pendingDeletion = null
        val pair = state.takeAtCaret() ?: return
        if (editor.isInsertMode && typstPairEnabled(c) &&
            pair.open.endOffset == caret.offset && pair.openChar == c
        ) {
            state.pendingDeletion = pair
            TabOutScopesTracker.getInstance().removeScopeEndingAt(editor, caret.offset)
        } else {
            state.restore(pair)
        }
    }

    override fun charDeleted(c: Char, file: PsiFile, editor: Editor): Boolean {
        if (file.fileType !is TypstFileTypeBase) return false
        val state = editor.caretModel.currentCaret.typstAutoClosingPairs()
        val pair = state?.pendingDeletion
        if (pair != null) {
            state.pendingDeletion = null
            try {
                val offset = editor.caretModel.offset
                if (pair.close.isValid && pair.close.startOffset == offset &&
                    editor.document.charsSequence.getOrNull(offset) == pair.closeChar
                ) editor.document.deleteString(offset, offset + 1)
            } finally {
                pair.dispose()
            }
        }
        // Keep normal smart Backspace, but suppress the platform's untracked-pair fallback.
        return pair != null || c in "([{\"'`"
    }
}
