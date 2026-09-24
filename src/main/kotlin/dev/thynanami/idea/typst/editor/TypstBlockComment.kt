package dev.thynanami.idea.typst.editor

import com.intellij.codeInsight.generation.SelfManagingCommenterUtil
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.util.text.CharArrayUtil

internal fun insertTypstBlockComment(
    startOffset: Int,
    endOffset: Int,
    document: Document,
    settings: CommonCodeStyleSettings,
): TextRange {
    val text = document.charsSequence
    val source = text.subSequence(startOffset, endOffset).toString()
    val wholeLines = startOffset < endOffset && (startOffset == 0 || text[startOffset - 1] == '\n') &&
        (endOffset == text.length || text[endOffset - 1] == '\n')
    if (!wholeLines && '\n' !in source) {
        val space = if (settings.BLOCK_COMMENT_ADD_SPACE) " " else ""
        return SelfManagingCommenterUtil.insertBlockComment(startOffset, endOffset, document, "/*$space", "$space*/")
    }

    val finalNewline = source.endsWith('\n')
    val lines = source.removeSuffix("\n").split('\n')
    val indent = if (wholeLines && !settings.BLOCK_COMMENT_AT_FIRST_COLUMN) commonTypstCommentIndent(lines) else ""
    val replacement = buildString {
        append(indent).append("/**\n")
        for (line in lines) {
            append(indent).append(" * ")
            // Keep blank-line whitespace verbatim, including lines shorter than the common indent.
            append(if (line.all { it == ' ' || it == '\t' }) line else line.removePrefix(indent))
            append('\n')
        }
        append(indent).append(" **/")
        if (finalNewline) append('\n')
    }
    document.replaceString(startOffset, endOffset, replacement)
    return TextRange(startOffset, startOffset + replacement.length)
}

internal fun uncommentTypstBlockComment(
    startOffset: Int,
    endOffset: Int,
    document: Document,
    settings: CommonCodeStyleSettings,
) {
    if (uncommentDecoratedTypstBlock(startOffset, endOffset, document)) return

    val text = document.charsSequence
    val openingLine = document.getLineNumber(startOffset)
    val closingLine = document.getLineNumber(endOffset - 1)
    val openingLineStart = document.getLineStartOffset(openingLine)
    val openingLineEnd = document.getLineEndOffset(openingLine)
    val closingLineStart = document.getLineStartOffset(closingLine)
    val closingLineEnd = document.getLineEndOffset(closingLine)
    var prefixStart = startOffset
    var prefixEnd = startOffset + 2
    var suffixStart = endOffset - 2
    var suffixEnd = endOffset
    if (openingLine != closingLine &&
        CharArrayUtil.isEmptyOrSpaces(text, openingLineStart, startOffset) &&
        CharArrayUtil.isEmptyOrSpaces(text, prefixEnd, openingLineEnd) &&
        CharArrayUtil.isEmptyOrSpaces(text, closingLineStart, suffixStart) &&
        CharArrayUtil.isEmptyOrSpaces(text, endOffset, closingLineEnd)
    ) {
        prefixStart = openingLineStart
        prefixEnd = openingLineEnd + 1
        suffixStart = closingLineStart
        suffixEnd = (closingLineEnd + 1).coerceAtMost(text.length)
        if (closingLineEnd == text.length && suffixStart > prefixEnd) suffixStart--
    } else if (settings.BLOCK_COMMENT_ADD_SPACE) {
        if (prefixEnd < suffixStart && text[prefixEnd] == ' ') prefixEnd++
        if (suffixStart > prefixEnd && text[suffixStart - 1] == ' ') suffixStart--
    }
    document.deleteString(suffixStart, suffixEnd)
    document.deleteString(prefixStart, prefixEnd)
}

private fun commonTypstCommentIndent(lines: List<String>): String {
    var common: String? = null
    for (line in lines) {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        if (indent.length == line.length) continue
        common = common?.commonPrefixWith(indent) ?: indent
        if (common.isEmpty()) break
    }
    return common.orEmpty()
}

private fun uncommentDecoratedTypstBlock(startOffset: Int, endOffset: Int, document: Document): Boolean {
    val text = document.charsSequence
    if (!CharArrayUtil.regionMatches(text, startOffset, "/**\n") ||
        !CharArrayUtil.regionMatches(text, endOffset - 3, "**/")
    ) return false

    val closingLineStart = document.getLineStartOffset(document.getLineNumber(endOffset - 1))
    val bodyStart = startOffset + 4
    if (closingLineStart <= bodyStart) return false
    val starIndent = text.subSequence(closingLineStart, endOffset - 3).toString()
    if (starIndent.any { it != ' ' && it != '\t' }) return false
    val openingLineStart = document.getLineStartOffset(document.getLineNumber(startOffset))
    val openingIndent = text.subSequence(openingLineStart, startOffset).toString()
    val indent = when {
        starIndent == " " || starIndent == "$openingIndent " -> starIndent.dropLast(1)
        // Keep comments generated before star alignment removable as well.
        starIndent.isEmpty() || starIndent == openingIndent -> starIndent
        else -> return false
    }

    val marker = "$starIndent* "
    val lines = text.subSequence(bodyStart, closingLineStart - 1).toString().split('\n')
    // Only strip stars when every line has our complete decoration, leaving ordinary comments intact.
    if (lines.any { !it.startsWith(marker) }) return false
    val finalNewline = endOffset < text.length && text[endOffset] == '\n'
    val replacement = buildString {
        for ((index, line) in lines.withIndex()) {
            if (index > 0) append('\n')
            val payload = line.substring(marker.length)
            if (payload.any { it != ' ' && it != '\t' }) append(indent)
            append(payload)
        }
        if (finalNewline) append('\n')
    }
    document.replaceString(startOffset - indent.length, endOffset + if (finalNewline) 1 else 0, replacement)
    return true
}
