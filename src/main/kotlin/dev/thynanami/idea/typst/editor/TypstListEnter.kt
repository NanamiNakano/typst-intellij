package dev.thynanami.idea.typst.editor

import com.intellij.application.options.CodeStyle
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateElementType

private val listItem = Regex("^([\\t ]*)([-+]|[0-9]+\\.)([\\t ]+)")

internal fun Editor.typstListEnter(file: PsiFile, offset: Int): Boolean {
    val text = document.charsSequence
    val options = CodeStyle.getIndentOptions(file)
    val tabSize = options.TAB_SIZE.coerceAtLeast(1)
    val lines = ListLines(this, tabSize)
    val line = lines[document.getLineNumber(offset)]
    val item = if (line.marker != null) line else lines.owner(line, offset) ?: return false
    val marker = item.marker ?: return false
    if (line === item && offset < item.start + item.indent.length + marker.length + 1) return false
    val markerScopes = typstScopeAt(item.start + item.indent.length).typstScopeNames().toList()
    val scopes = listCaretScopes(offset)
    if (scopes.any(::isListLiteralScope)) return false
    // Some grammar blocks have no named enclosing scope; their delimiters still distinguish script from markup.
    if (!balancedListBraces(item.start, offset)) return false
    if (scopes.count { it.startsWith("meta.expr.") } > markerScopes.count { it.startsWith("meta.expr.") } &&
        !(offset == text.length && text[offset - 1] in ")]}")
    ) return false

    if (line === item && item.text.substring(item.bodyOffset).isBlank() && !lines.hasBody(item)) {
        val parent = lines.owner(item, item.start)
        val replacement = parent?.let { "${it.indent}$marker " } ?: item.indent
        typstEnterEdit(item.start, item.end, replacement)
    } else if (line !== item && offset == line.start + line.indent.length) {
        // At the beginning of a continuation, Enter promotes its text (or the empty line) to a sibling.
        val nextMarker = if (marker.endsWith('.')) "${marker.dropLast(1).toBigInteger() + java.math.BigInteger.ONE}." else marker
        val separator = if (lines.isLoose(item) && line.number > 0 && lines[line.number - 1].text.isNotBlank()) "\n" else ""
        typstEnterEdit(line.start, offset, "$separator${item.indent}$nextMarker ")
    } else {
        val bodyWidth = typstIndentWidth(item.text.take(item.bodyOffset), tabSize)
        val bodyIndent = if (options.USE_TAB_CHARACTER) {
            "\t".repeat(bodyWidth / tabSize) + " ".repeat(bodyWidth % tabSize)
        } else {
            item.indent + " ".repeat(bodyWidth - item.width)
        }
        val whitespace = text.subSequence(offset, line.end).takeWhile { it == ' ' || it == '\t' }.length
        typstEnterEdit(offset, offset + whitespace, "\n$bodyIndent")
    }
    return true
}

private fun Editor.isListMarker(offset: Int): Boolean =
    typstScopeAt(offset).typstScopeNames().any { it.startsWith("punctuation.definition.list.") }

private data class ListLine(
    val number: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val indent: String,
    val width: Int,
    val marker: String?,
    val bodyOffset: Int,
) {
    fun isSiblingOf(item: ListLine): Boolean =
        marker != null && width == item.width && (marker == "-") == (item.marker == "-")
}

/** Physical lines in the same delimiter context; TextMate has no enclosing list or block nodes. */
private class ListLines(private val editor: Editor, private val tabSize: Int) {
    private val document = editor.document
    private val cache = mutableMapOf<Int, ListLine>()

    operator fun get(number: Int): ListLine = cache.getOrPut(number) {
        val start = document.getLineStartOffset(number)
        val end = document.getLineEndOffset(number)
        val text = document.charsSequence.subSequence(start, end).toString()
        val indent = text.takeWhile { it == ' ' || it == '\t' }
        val match = listItem.find(text)?.takeIf { editor.isListMarker(start + indent.length) }
        ListLine(
            number, start, end, text, indent, typstIndentWidth(indent, tabSize),
            match?.groupValues?.get(2), match?.value?.length ?: indent.length,
        )
    }

    fun owner(line: ListLine, offset: Int): ListLine? {
        var width = line.width
        if (width == 0) return null
        for (previous in before(line, offset)) {
            if (previous.text.isBlank()) continue
            if (previous.marker != null && previous.width < width) return previous
            width = minOf(width, previous.width)
            if (width == 0) break
        }
        return null
    }

    fun hasBody(item: ListLine): Boolean {
        // Even a blank marker line can own a following paragraph, raw block, or nested list.
        val next = after(item).firstOrNull { it.text.isNotBlank() } ?: return false
        return next.width > item.width
    }

    fun isLoose(item: ListLine): Boolean {
        var blank = false
        var gap = true
        for (previous in before(item, item.start)) {
            if (previous.text.isBlank()) {
                if (gap) blank = true
            } else if (previous.width > item.width) {
                gap = false
            } else {
                if (!previous.isSiblingOf(item)) break
                if (blank) return true
                gap = true
            }
        }
        blank = false
        for (next in after(item)) {
            if (next.text.isBlank()) {
                blank = true
            } else if (next.width > item.width) {
                blank = false
            } else {
                if (!next.isSiblingOf(item)) break
                if (blank) return true
            }
        }
        return false
    }

    private fun before(line: ListLine, offset: Int): Sequence<ListLine> = sequence {
        val braces = ListBraces(editor, backwards = true)
        if (!braces.scan(line.start, offset)) return@sequence
        for (number in line.number - 1 downTo 0) {
            val previous = get(number)
            if (!braces.scan(previous.start, previous.end)) break
            if (braces.isEmpty && editor.listCaretScopes(previous.start).none(::isListLiteralScope)) yield(previous)
        }
    }

    private fun after(line: ListLine): Sequence<ListLine> = sequence {
        val braces = ListBraces(editor, backwards = false)
        if (!braces.scan(line.start, line.end)) return@sequence
        for (number in line.number + 1 until document.lineCount) {
            val next = get(number)
            // An indented enclosing closer is not a body line of an otherwise empty item.
            if (braces.isEmpty && next.text.getOrNull(next.indent.length) in listOf(')', ']', '}') &&
                editor.typstScopeAt(next.start + next.indent.length).typstScopeNames().any { it.startsWith("meta.brace.") }
            ) break
            if (braces.isEmpty && editor.listCaretScopes(next.start).none(::isListLiteralScope)) yield(next)
            if (!braces.scan(next.start, next.end)) break
        }
    }
}

private fun isListLiteralScope(scope: String): Boolean =
    scope.startsWith("markup.raw.") || scope.startsWith("string.") || scope.startsWith("comment.") ||
        scope == "markup.math.typst" || scope.startsWith("constant.character.escape.")

private fun Editor.listCaretScopes(offset: Int): List<String> {
    typstScopeAt(offset)?.let { scope ->
        // A caret immediately before an opening delimiter is still outside that literal.
        val previous = generateSequence(typstScopeAt(offset - 1)) { it.parent }.toList()
        val opening = generateSequence(scope) { it.parent }.lastOrNull { context ->
            previous.none { it === context } && context.scopeName?.split(' ')?.any(::isListLiteralScope) == true
        }
        return (if (opening != null) opening.parent else scope).typstScopeNames().toList()
    }
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
    val braces = ListBraces(this, backwards = false)
    return braces.scan(start, end) && braces.isEmpty
}

/** Stop at an enclosing delimiter, while skipping balanced embedded code/content in either direction. */
private class ListBraces(private val editor: Editor, private val backwards: Boolean) {
    private val stack = mutableListOf<Char>()
    val isEmpty: Boolean get() = stack.isEmpty()

    fun scan(start: Int, end: Int): Boolean {
        if (start == end) return true
        val iterator = editor.highlighter.createIterator(if (backwards) end - 1 else start)
        val opening = if (backwards) ")]}" else "([{"
        val closing = if (backwards) "([{" else ")]}"
        while (!iterator.atEnd() && iterator.start < end && iterator.end > start) {
            val scopes = (iterator.tokenType as? TextMateElementType)?.scope.typstScopeNames().toList()
            if (scopes.any { it.startsWith("meta.brace.") } && scopes.none(::isListLiteralScope)) {
                val from = maxOf(start, iterator.start)
                val to = minOf(end, iterator.end)
                val indices = if (backwards) to - 1 downTo from else from until to
                for (index in indices) {
                    val char = editor.document.charsSequence[index]
                    if (char in opening) {
                        stack += char
                    } else if (char in closing) {
                        if (stack.lastOrNull() != opening[closing.indexOf(char)]) return false
                        stack.removeAt(stack.lastIndex)
                    }
                }
            }
            if (backwards) iterator.retreat() else iterator.advance()
        }
        return true
    }
}
