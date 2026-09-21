package dev.thynanami.idea.typst.editor

import com.intellij.openapi.editor.Editor

private val lineComment = Regex("^([\\t ]*)(/{2,}!?)(.*)$")
private val blockCommentStart = Regex("^[\\t ]*/\\*[*!](?!/)(?:[^*]|\\*(?!/))*$")
private val blockCommentLine = Regex("^[\\t ]+\\*(?:[\\t ](?:[^*]|\\*(?!/))*)?$")
private val blockCommentEnd = Regex("^[\\t ]+\\*/[\\t ]*$")

internal fun Editor.typstCommentEnter(offset: Int): Boolean {
    val text = document.charsSequence
    val line = document.getLineNumber(offset)
    val start = document.getLineStartOffset(line)
    val end = document.getLineEndOffset(line)
    val before = text.subSequence(start, offset).toString()
    val after = text.subSequence(offset, end).toString()
    val indent = before.takeWhile { it == ' ' || it == '\t' }
    val scopes = typstScopeAt(start + indent.length).typstScopeNames().toList()
    if (scopes.any { it.startsWith("markup.raw.") || it.startsWith("string.") }) return false

    if ("comment.line.double-slash.typst" in scopes) {
        val match = lineComment.matchEntire(before) ?: return false
        val prefix = match.groupValues[2]
        if (prefix == "//" && !hasAdjacentLineComment(line, indent)) return false
        typstEnterEdit(offset, offset + after.takeWhile { it == ' ' || it == '\t' }.length, "\n$indent$prefix ")
        return true
    }
    if ("comment.block.typst" !in scopes) return false
    if (blockCommentStart.matches(before)) {
        val prefix = "\n$indent * "
        if (after.trim() == "*/") {
            typstEnterEdit(offset, end, "$prefix\n$indent */", prefix.length)
        } else {
            typstEnterEdit(offset, offset + after.takeWhile { it == ' ' || it == '\t' }.length, prefix)
        }
        return true
    }
    if (blockCommentLine.matches(before)) {
        typstEnterEdit(offset, offset + after.takeWhile { it == ' ' || it == '\t' }.length, "\n$indent* ")
        return true
    }
    if (blockCommentEnd.matches(before)) {
        // The leading space aligns the asterisks with /**; it is not the containing block's indent.
        typstEnterEdit(offset, offset, "\n${indent.removeSuffix(" ")}")
        return true
    }
    return false
}

private fun Editor.hasAdjacentLineComment(line: Int, indent: String): Boolean =
    sequenceOf(-1, 1).any { direction ->
        val adjacent = generateSequence(line + direction) { it + direction }
            .takeWhile { it in 0 until document.lineCount }
            .firstOrNull {
                document.charsSequence.subSequence(document.getLineStartOffset(it), document.getLineEndOffset(it)).isNotBlank()
            } ?: return@any false
        val start = document.getLineStartOffset(adjacent)
        val match = lineComment.matchEntire(document.charsSequence.subSequence(start, document.getLineEndOffset(adjacent)))
        match != null && match.groupValues[1] == indent && typstScopeAt(start + indent.length)
            .typstScopeNames().any { it == "comment.line.double-slash.typst" }
    }
