package dev.thynanami.idea.typst.grazie

import com.intellij.grazie.text.TextContent
import com.intellij.grazie.text.TextContent.TextDomain
import com.intellij.grazie.text.TextExtractor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement

class TypstTextExtractor : TextExtractor() {
    override fun buildTextContents(element: PsiElement, allowedDomains: Set<TextDomain>): List<TextContent> =
        typstTextRegions(element).mapNotNull { region ->
            val domain = when (region.domain) {
                TypstTextDomain.PROSE -> TextDomain.PLAIN_TEXT
                TypstTextDomain.COMMENT -> TextDomain.COMMENTS
                TypstTextDomain.STRING -> TextDomain.LITERALS
            }
            if (domain !in allowedDomains) return@mapNotNull null

            val parts = mutableListOf<TextContent>()
            val exclusions = mutableListOf<TextContent.Exclusion>()
            var start = region.range.startOffset

            fun append(end: Int) {
                if (end > start) {
                    // Keep lexer offsets intact until all exclusions have been applied.
                    parts += TextContent.psiFragment(domain, element, TextRange(start, end)).excludeRanges(exclusions)
                }
                exclusions.clear()
            }

            for ((range, exclusionKind) in region.exclusions) {
                if (exclusionKind == TypstTextExclusionKind.WHITESPACE) {
                    append(range.startOffset)
                    start = range.endOffset
                } else {
                    val kind = when (exclusionKind) {
                        TypstTextExclusionKind.MARKUP -> TextContent.ExclusionKind.markup
                        else -> TextContent.ExclusionKind.unknown
                    }
                    exclusions += TextContent.Exclusion(
                        range.startOffset - start, range.endOffset - start, kind,
                    )
                }
            }
            append(region.range.endOffset)
            TextContent.joinWithWhitespace(' ', parts)?.trimWhitespace()
        }
}
