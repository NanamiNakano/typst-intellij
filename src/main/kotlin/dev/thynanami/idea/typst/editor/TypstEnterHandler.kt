package dev.thynanami.idea.typst.editor

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.AutoHardWrapHandler
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.editor.actions.SplitLineAction
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiFile
import com.intellij.util.text.CharArrayUtil
import dev.thynanami.idea.typst.TypstFileTypeBase

class TypstEnterHandler : EnterHandlerDelegate {
    override fun preprocessEnter(
        file: PsiFile,
        editor: Editor,
        caretOffset: Ref<Int>,
        caretAdvance: Ref<Int>,
        dataContext: DataContext,
        originalHandler: EditorActionHandler?,
    ): EnterHandlerDelegate.Result {
        if (file.fileType !is TypstFileTypeBase || !editor.isInsertMode ||
            !CodeInsightSettings.getInstance().SMART_INDENT_ON_ENTER ||
            DataManager.getInstance().loadFromDataContext(dataContext, AutoHardWrapHandler.AUTO_WRAP_LINE_IN_PROGRESS_KEY) == true ||
            DataManager.getInstance().loadFromDataContext(dataContext, SplitLineAction.SPLIT_LINE_KEY) == true
        ) return EnterHandlerDelegate.Result.Continue

        val offset = caretOffset.get()
        return if (editor.typstCommentEnter(offset) || editor.enterPair(file, offset) || editor.typstListEnter(file, offset)) {
            caretOffset.set(editor.caretModel.offset)
            EnterHandlerDelegate.Result.Stop
        } else {
            EnterHandlerDelegate.Result.Continue
        }
    }
}

private fun Editor.enterPair(file: PsiFile, offset: Int): Boolean {
    val text = document.charsSequence
    // Expand only same-line pairs; preserve normal Enter behavior in existing multiline pairs.
    val left = CharArrayUtil.shiftBackward(text, offset - 1, " \t")
    if (left < 0) return false
    val opening = text[left]
    val closing = when (opening) {
        '(' -> ')'
        '[' -> ']'
        '{' -> '}'
        '$' -> '$'
        else -> return false
    }
    val leftScopes = typstScopeAt(left).typstScopeNames().toList()
    if (leftScopes.isEmpty() || leftScopes.any {
            it.startsWith("string.") || it.startsWith("comment.") || it.startsWith("markup.raw.") ||
                it.startsWith("constant.character.escape.")
        }) return false
    if (opening == '$' && "punctuation.definition.string.begin.math.typst" !in leftScopes) return false

    val lineStart = document.getLineStartOffset(document.getLineNumber(left))
    val baseIndent = text.subSequence(lineStart, left).takeWhile { it == ' ' || it == '\t' }.toString()
    val options = CodeStyle.getIndentOptions(file)
    val tabSize = options.TAB_SIZE.coerceAtLeast(1)
    val width = typstIndentWidth(baseIndent, tabSize) + options.INDENT_SIZE.coerceAtLeast(0)
    val innerIndent = if (options.USE_TAB_CHARACTER) {
        "\t".repeat(width / tabSize) + " ".repeat(width % tabSize)
    } else {
        " ".repeat(width)
    }
    val right = CharArrayUtil.shiftForward(text, offset, " \t")
    val rightScopes = typstScopeAt(right).typstScopeNames().toList()
    val isPair = right < text.length && text[right] == closing &&
        rightScopes.none { it.startsWith("string.") || it.startsWith("comment.") || it.startsWith("markup.raw.") } &&
        (opening != '$' || "punctuation.definition.string.end.math.typst" in rightScopes &&
            generateSequence(typstScopeAt(left)) { it.parent }.firstOrNull { it.scopeName == "markup.math.typst" } ===
            generateSequence(typstScopeAt(right)) { it.parent }.firstOrNull { it.scopeName == "markup.math.typst" })
    if (isPair) {
        typstEnterEdit(left + 1, right, "\n$innerIndent\n$baseIndent", 1 + innerIndent.length)
        return true
    }
    if (opening == '$') return false
    typstEnterEdit(offset, right, "\n$innerIndent")
    return true
}

internal fun Editor.typstEnterEdit(start: Int, end: Int, text: String, caretAdvance: Int = text.length) {
    document.replaceString(start, end, text)
    caretModel.moveToOffset(start + caretAdvance)
    selectionModel.removeSelection()
    scrollingModel.scrollToCaret(ScrollType.RELATIVE)
}

internal fun typstIndentWidth(indent: CharSequence, tabSize: Int): Int =
    indent.fold(0) { column, char -> if (char == '\t') column + tabSize - column % tabSize else column + 1 }
