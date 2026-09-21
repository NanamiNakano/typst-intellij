package dev.thynanami.idea.typst.editor

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateElementType

private val listItem = Regex("^([\\t ]*)([-+]|[0-9]+\\.)([\\t ]+)")

internal fun Editor.typstListEnter(file: PsiFile, offset: Int): Boolean {
    val text = document.charsSequence
    val line = document.getLineNumber(offset)
    val start = document.getLineStartOffset(line)
    val end = document.getLineEndOffset(line)
    val lineText = text.subSequence(start, end).toString()
    val match = listItem.find(lineText) ?: return false
    val indent = match.groupValues[1]
    val marker = match.groupValues[2]
    if (offset < start + indent.length + marker.length + 1) return false
    val markerScopes = typstScopeAt(start + indent.length).typstScopeNames().toList()
    if (markerScopes.none { it.startsWith("punctuation.definition.list.") }) return false
    val scopes = listCaretScopes(offset)
    if (scopes.any {
            it.startsWith("markup.raw.") || it.startsWith("string.") || it.startsWith("comment.") ||
                it == "markup.math.typst" || it.startsWith("constant.character.escape.")
        }) return false
    // Some grammar blocks have no named enclosing scope; their delimiters still distinguish script from markup.
    if (!balancedListBraces(start, offset)) return false
    if (scopes.count { it.startsWith("meta.expr.") } > markerScopes.count { it.startsWith("meta.expr.") } &&
        !(offset == text.length && text[offset - 1] in ")]}")
    ) return false

    if (lineText.substring(match.range.last + 1).isBlank()) {
        val tabSize = CodeStyle.getIndentOptions(file).TAB_SIZE.coerceAtLeast(1)
        val parentIndent = listParentIndent(line, indent, tabSize)
        val replacement = parentIndent?.let { "$it$marker " } ?: indent
        typstEnterEdit(start, end, replacement)
    } else {
        val nextMarker = if (marker.endsWith('.')) "${marker.dropLast(1).toBigInteger() + java.math.BigInteger.ONE}." else marker
        val whitespace = text.subSequence(offset, end).takeWhile { it == ' ' || it == '\t' }.length
        typstEnterEdit(offset, offset + whitespace, "\n$indent$nextMarker ")
    }
    return true
}

private fun Editor.isListMarker(offset: Int): Boolean =
    typstScopeAt(offset).typstScopeNames().any { it.startsWith("punctuation.definition.list.") }

private fun Editor.listParentIndent(line: Int, indent: String, tabSize: Int): String? {
    val width = typstIndentWidth(indent, tabSize)
    val text = document.charsSequence
    for (previous in line - 1 downTo 0) {
        val start = document.getLineStartOffset(previous)
        val previousText = text.subSequence(start, document.getLineEndOffset(previous)).toString()
        if (previousText.isBlank()) continue
        val previousIndent = previousText.takeWhile { it == ' ' || it == '\t' }
        val previousWidth = typstIndentWidth(previousIndent, tabSize)
        val match = listItem.find(previousText)
        if (match != null && isListMarker(start + previousIndent.length)) {
            if (previousWidth < width) return previousIndent
        } else if (previousWidth < width || !balancedListBraces(start, document.getLineEndOffset(previous))) {
            // A containing block or an earlier paragraph ends this list's ancestry.
            return null
        }
    }
    return null
}

private fun Editor.listCaretScopes(offset: Int): List<String> {
    typstScopeAt(offset)?.let { return it.typstScopeNames().toList() }
    val scope = typstScopeAt(offset - 1)
    val names = scope.typstScopeNames().toList()
    val ended = when {
        "punctuation.definition.string.end.math.typst" in names -> "markup.math.typst"
        "punctuation.definition.raw.end.typst" in names -> "markup.raw.block.typst"
        "punctuation.definition.raw.inline.typst" in names -> "markup.raw.inline.typst"
        "punctuation.definition.string.typst" in names -> "string.quoted.double.typst"
        else -> return names
    }
    val context = generateSequence(scope) { it.parent }.firstOrNull { it.scopeName?.toString()?.split(' ')?.contains(ended) == true }
        ?: return names
    if (generateSequence(typstScopeAt(offset - 2)) { it.parent }.none { it === context }) return names
    return context.parent.typstScopeNames().toList()
}

private fun Editor.balancedListBraces(start: Int, end: Int): Boolean {
    val iterator = highlighter.createIterator(start)
    val stack = mutableListOf<Char>()
    while (!iterator.atEnd() && iterator.start < end) {
        val scopes = (iterator.tokenType as? TextMateElementType)?.scope.typstScopeNames().toList()
        if (scopes.any { it.startsWith("meta.brace.") } && scopes.none { it.startsWith("markup.raw.") || it.startsWith("string.") || it.startsWith("comment.") }) {
            for (index in iterator.start until minOf(iterator.end, end)) {
                when (val char = document.charsSequence[index]) {
                    '(', '[', '{' -> stack += char
                    ')', ']', '}' -> if (stack.lastOrNull() == "([{"[")]}".indexOf(char)]) stack.removeAt(stack.lastIndex) else return false
                }
            }
        }
        iterator.advance()
    }
    return stack.isEmpty()
}
