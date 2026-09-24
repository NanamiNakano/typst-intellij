package dev.thynanami.idea.typst.editor

import com.intellij.application.options.CodeStyle
import com.intellij.codeInsight.generation.CommenterDataHolder
import com.intellij.codeInsight.generation.SelfManagingCommenter
import com.intellij.lang.Commenter
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.util.DocumentUtil
import com.intellij.util.text.CharArrayUtil
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateElementType

internal class TypstCommenter : Commenter, SelfManagingCommenter<TypstCommentState> {
    override fun getLineCommentPrefix() = "//"

    override fun getBlockCommentPrefix() = "/*"

    override fun getBlockCommentSuffix() = "*/"

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null

    override fun createLineCommentingState(
        startLine: Int,
        endLine: Int,
        document: Document,
        file: PsiFile,
    ) = TypstCommentState(file, firstLine = startLine, singleLine = startLine == endLine)

    override fun createBlockCommentingState(
        selectionStart: Int,
        selectionEnd: Int,
        document: Document,
        file: PsiFile,
    ) = TypstCommentState(file, blockComments = typstBlockCommentRanges(document.charsSequence, file))

    override fun getCommentPrefix(line: Int, document: Document, data: TypstCommentState) = "//"

    override fun isLineCommented(line: Int, offset: Int, document: Document, data: TypstCommentState): Boolean {
        val commented = CharArrayUtil.regionMatches(document.charsSequence, offset, "//")
        if (commented) data.firstCommentedLine = minOf(data.firstCommentedLine, line)
        return commented
    }

    override fun commentLine(line: Int, offset: Int, document: Document, data: TypstCommentState) {
        val text = document.charsSequence
        val contentStart = CharArrayUtil.shiftForward(text, offset, " \t")
        val addSpace = data.settings.LINE_COMMENT_ADD_SPACE &&
            (data.singleLine || contentStart < document.getLineEndOffset(line))
        val column = offset - document.getLineStartOffset(line)
        data.editLine(document, line == data.firstLine) {
            document.insertString(document.getLineStartOffset(line) + column, if (addSpace) "// " else "//")
        }
    }

    override fun uncommentLine(line: Int, offset: Int, document: Document, data: TypstCommentState) {
        val column = offset - document.getLineStartOffset(line)
        data.editLine(document, line == data.firstCommentedLine) {
            val start = document.getLineStartOffset(line) + column
            val text = document.charsSequence
            var end = start + 2
            if (data.settings.LINE_COMMENT_ADD_SPACE && end < text.length && text[end] == ' ') end++
            document.deleteString(start, end)
            val lineStart = document.getLineStartOffset(line)
            val lineEnd = document.getLineEndOffset(line)
            if (CharArrayUtil.isEmptyOrSpaces(document.charsSequence, lineStart, lineEnd)) {
                document.deleteString(lineStart, lineEnd)
            }
        }
    }

    override fun getBlockCommentPrefix(selectionStart: Int, document: Document, data: TypstCommentState) = "/*"

    override fun getBlockCommentSuffix(selectionEnd: Int, document: Document, data: TypstCommentState) = "*/"

    override fun getBlockCommentRange(
        selectionStart: Int,
        selectionEnd: Int,
        document: Document,
        data: TypstCommentState,
    ): TextRange? {
        if (selectionStart == selectionEnd) {
            return data.blockComments.filter { selectionStart in it.startOffset until it.endOffset }
                .minByOrNull { it.length }
        }
        val text = document.charsSequence
        var start = selectionStart
        var end = selectionEnd
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        return data.blockComments.firstOrNull { it.startOffset == start && it.endOffset == end }
    }

    override fun insertBlockComment(
        startOffset: Int,
        endOffset: Int,
        document: Document,
        data: TypstCommentState,
    ): TextRange = insertTypstBlockComment(startOffset, endOffset, document, data.settings)

    override fun uncommentBlockComment(
        startOffset: Int,
        endOffset: Int,
        document: Document,
        data: TypstCommentState,
    ) = uncommentTypstBlockComment(startOffset, endOffset, document, data.settings)
}

internal class TypstCommentState(
    file: PsiFile,
    val firstLine: Int = 0,
    val singleLine: Boolean = false,
    val blockComments: List<TextRange> = emptyList(),
) : CommenterDataHolder() {
    val settings = CodeStyle.getLanguageSettings(file, file.language)
    var firstCommentedLine = Int.MAX_VALUE
    private val edits = mutableListOf<() -> Unit>()

    fun editLine(document: Document, last: Boolean, edit: () -> Unit) {
        edits += edit
        if (!last) return
        try {
            // The platform visits lines bottom-to-top, but only batches selections over 100 lines.
            // Apply our edits together so TextMate re-highlights once for smaller selections too.
            DocumentUtil.executeInBulk(document, document.isInBulkUpdate || edits.size > 1) {
                edits.forEach { it() }
            }
        } finally {
            edits.clear()
        }
    }
}

// The lightweight PSI contains no PsiComment nodes; use the same syntax information as the editor.
private fun typstBlockCommentRanges(text: CharSequence, file: PsiFile): List<TextRange> {
    val lexer = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, file.project, file.virtualFile)
        ?.highlightingLexer ?: return emptyList()
    val starts = ArrayDeque<Int>()
    val ranges = mutableListOf<TextRange>()
    var offset = 0
    lexer.start(text)
    while (lexer.tokenType != null) {
        ProgressManager.checkCanceled()
        val scopes = (lexer.tokenType as? TextMateElementType)?.scope.typstScopeNames().toList()
        if ("comment.block.typst" in scopes && scopes.none { it.startsWith("markup.raw.") || it.startsWith("string.") }) {
            offset = maxOf(offset, lexer.tokenStart)
            while (offset < lexer.tokenEnd && offset + 1 < text.length) {
                if (offset and 4095 == 0) ProgressManager.checkCanceled()
                when {
                    text[offset] == '/' && text[offset + 1] == '*' -> {
                        starts.addLast(offset)
                        offset += 2
                    }
                    text[offset] == '*' && text[offset + 1] == '/' -> {
                        starts.removeLastOrNull()?.let { ranges += TextRange(it, offset + 2) }
                        offset += 2
                    }
                    else -> offset++
                }
            }
        }
        lexer.advance()
    }
    return ranges
}
