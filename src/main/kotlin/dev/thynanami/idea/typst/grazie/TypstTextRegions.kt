package dev.thynanami.idea.typst.grazie

import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateElementType
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateScope

internal enum class TypstTextDomain { PROSE, COMMENT, STRING }

internal enum class TypstTextExclusionKind { MARKUP, UNKNOWN, WHITESPACE }

internal data class TypstTextExclusion(val range: TextRange, val kind: TypstTextExclusionKind)

internal data class TypstTextRegion(
    val range: TextRange,
    val domain: TypstTextDomain,
    val exclusions: List<TypstTextExclusion>,
)

private data class TypstTextToken(
    val range: TextRange,
    val domain: TypstTextDomain,
    val exclusion: TypstTextExclusionKind? = null,
    val context: TextMateScope? = null,
)

private val paragraphBreak = Regex("\n[\t ]*\n")
private val codeScopes = listOf("constant.", "entity.", "keyword.", "variable.", "storage.", "support.", "string.", "meta.brace.")

/** Ranges are relative to the single content leaf supplied by the lightweight parser. */
internal fun typstTextRegions(element: PsiElement): List<TypstTextRegion> {
    if (element is PsiFile || element.firstChild != null) return emptyList()
    val file = element.containingFile
    val lexer = SyntaxHighlighterFactory.getSyntaxHighlighter(file.language, file.project, file.virtualFile)
        ?.highlightingLexer ?: return emptyList()
    val text = element.text
    val tokens = mutableListOf<TypstTextToken>()
    lexer.start(text)
    while (lexer.tokenType != null) {
        ProgressManager.checkCanceled()
        val scopes = generateSequence((lexer.tokenType as? TextMateElementType)?.scope) { it.parent }.toList()
        val names = scopes.flatMap { it.scopeName?.toString()?.split(' ').orEmpty() }
        val range = TextRange(lexer.tokenStart, lexer.tokenEnd)
        val domain = when {
            names.any { it.startsWith("markup.raw.") || it == "markup.math.typst" || it == "comment.line.shebang.typst" } -> TypstTextDomain.PROSE
            names.any { it.startsWith("comment.") } -> TypstTextDomain.COMMENT
            "string.quoted.double.typst" in names -> TypstTextDomain.STRING
            else -> TypstTextDomain.PROSE
        }
        val context = when (domain) {
            TypstTextDomain.COMMENT -> scopes.firstOrNull { it.scopeName?.startsWith("comment.") == true }
            TypstTextDomain.STRING -> scopes.firstOrNull { it.scopeName?.toString() == "string.quoted.double.typst" }
            TypstTextDomain.PROSE -> null
        }
        val exclusion = textExclusion(names, domain, range.subSequence(text))
        // Tinymist scopes opening comment delimiters separately, but not closing ones.
        if (domain == TypstTextDomain.COMMENT && "comment.block.typst" in names && range.subSequence(text).endsWith("*/")) {
            val end = range.endOffset - 2
            if (end > range.startOffset) tokens += TypstTextToken(TextRange(range.startOffset, end), domain, exclusion, context)
            tokens += TypstTextToken(TextRange(end, range.endOffset), domain, TypstTextExclusionKind.MARKUP, context)
        } else {
            tokens += TypstTextToken(range, domain, exclusion, context)
        }
        lexer.advance()
    }

    val result = mutableListOf<TypstTextRegion>()
    // Preserve a paragraph's context around inline expressions, references, and markup.
    var tokenIndex = 0
    var start = 0
    val paragraphEnds = paragraphBreak.findAll(text).map { it.range.last + 1 } + sequenceOf(text.length)
    for (end in paragraphEnds) {
        ProgressManager.checkCanceled()
        val exclusions = mutableListOf<TypstTextExclusion>()
        var hasText = false
        while (tokenIndex < tokens.size && tokens[tokenIndex].range.startOffset < end) {
            val token = tokens[tokenIndex]
            val range = token.range.intersection(TextRange(start, end))!!
            val kind = if (token.domain == TypstTextDomain.PROSE) token.exclusion else TypstTextExclusionKind.UNKNOWN
            if (kind == null) {
                hasText = hasText || range.subSequence(text).any { !it.isWhitespace() }
            } else {
                exclusions += TypstTextExclusion(range, kind)
            }
            if (token.range.endOffset > end) break
            tokenIndex++
        }
        if (hasText) result += TypstTextRegion(TextRange(start, end), TypstTextDomain.PROSE, exclusions)
        start = end
    }

    var index = 0
    while (index < tokens.size) {
        ProgressManager.checkCanceled()
        val first = tokens[index]
        if (first.domain == TypstTextDomain.PROSE) {
            index++
            continue
        }
        val exclusions = mutableListOf<TypstTextExclusion>()
        var end = first.range.endOffset
        // TextMate creates a distinct scope instance for each string/comment occurrence.
        while (index < tokens.size && tokens[index].domain == first.domain && tokens[index].context === first.context) {
            val token = tokens[index++]
            token.exclusion?.let { exclusions += TypstTextExclusion(token.range, it) }
            end = token.range.endOffset
        }
        result += TypstTextRegion(TextRange(first.range.startOffset, end), first.domain, exclusions)
    }
    return result
}

private fun textExclusion(names: List<String>, domain: TypstTextDomain, text: CharSequence): TypstTextExclusionKind? {
    if (names.isEmpty() || names.any {
            it.startsWith("markup.raw.") || it == "markup.math.typst" ||
                it == "comment.line.shebang.typst" || it == "markup.underline.link.typst"
        }) return TypstTextExclusionKind.UNKNOWN
    if (domain == TypstTextDomain.COMMENT) {
        return if ("punctuation.definition.comment.typst" in names) TypstTextExclusionKind.MARKUP else null
    }
    if (domain == TypstTextDomain.STRING) {
        when (names.firstOrNull { it.startsWith("meta.expr.") }) {
            "meta.expr.import.typst", "meta.expr.include.typst" -> return TypstTextExclusionKind.UNKNOWN
        }
        return when {
            "punctuation.definition.string.typst" in names -> TypstTextExclusionKind.MARKUP
            "constant.character.escape.string.typst" in names ->
                when (text.toString()) {
                    "\\n", "\\r", "\\t" -> TypstTextExclusionKind.WHITESPACE
                    else -> TypstTextExclusionKind.UNKNOWN
                }
            else -> null
        }
    }
    for (name in names) {
        when {
            name == "constant.character.escape.content.typst" ->
                return if (text.length == 2 && text[1].isWhitespace()) TypstTextExclusionKind.WHITESPACE else TypstTextExclusionKind.UNKNOWN
            name == "punctuation.definition.nonbreaking-space.typst" || name == "punctuation.definition.linebreak.typst" ->
                return TypstTextExclusionKind.WHITESPACE
            name == "string.other.label.typst" || name == "punctuation.definition.heading.typst" ||
                name.startsWith("punctuation.definition.list.") || name == "meta.brace.square.typst" ->
                return TypstTextExclusionKind.MARKUP
            name.startsWith("punctuation.") || codeScopes.any(name::startsWith) -> return TypstTextExclusionKind.UNKNOWN
            name == "source.typst" || name == "source.typst-code" -> return null
        }
    }
    return TypstTextExclusionKind.UNKNOWN
}
