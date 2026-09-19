package dev.thynanami.idea.typst.languageserver

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.psi.PsiFile
import dev.thynanami.idea.typst.TypstColor
import dev.thynanami.idea.typst.isTypstFile
import org.eclipse.lsp4j.SemanticTokenModifiers
import org.eclipse.lsp4j.SemanticTokenTypes

private const val BOOLEAN = "bool"
private const val PUNCTUATION = "punct"
private const val ESCAPE = "escape"
private const val LINK = "link"
private const val RAW = "raw"
private const val LABEL = "label"
private const val REFERENCE = "ref"
private const val HEADING = "heading"
private const val LIST_MARKER = "marker"
private const val LIST_TERM = "term"
private const val MATH_DELIMITER = "delim"
private const val INTERPOLATED = "pol"
private const val SYNTAX_ERROR = "error"
private const val MARKUP_TEXT = "text"

private const val STRONG = "strong"
private const val EMPHASIS = "emph"
private const val MATH = "math"

class TypstSemanticTokensSupport : LspSemanticTokensSupport() {
    override val tokenTypes: List<String> = listOf(
        SemanticTokenTypes.Comment,
        SemanticTokenTypes.String,
        SemanticTokenTypes.Keyword,
        SemanticTokenTypes.Operator,
        SemanticTokenTypes.Number,
        SemanticTokenTypes.Function,
        SemanticTokenTypes.Decorator,
        SemanticTokenTypes.Type,
        SemanticTokenTypes.Namespace,
        BOOLEAN,
        PUNCTUATION,
        ESCAPE,
        LINK,
        RAW,
        LABEL,
        REFERENCE,
        HEADING,
        LIST_MARKER,
        LIST_TERM,
        MATH_DELIMITER,
        INTERPOLATED,
        SYNTAX_ERROR,
        MARKUP_TEXT,
    )

    override val tokenModifiers: List<String> = listOf(
        STRONG,
        EMPHASIS,
        MATH,
        SemanticTokenModifiers.Readonly,
        SemanticTokenModifiers.Static,
        SemanticTokenModifiers.DefaultLibrary,
    )

    override fun shouldAskServerForSemanticTokens(psiFile: PsiFile): Boolean =
        psiFile.virtualFile?.isTypstFile() == true

    override fun getTextAttributesKey(
        tokenType: String,
        modifiers: List<String>,
    ): TextAttributesKey? =
        (colorOfTokenType(tokenType, modifiers) ?: colorOfModifiers(modifiers))?.attributes

    private fun colorOfTokenType(tokenType: String, modifiers: List<String>): TypstColor? =
        when (tokenType) {
            SemanticTokenTypes.Comment -> TypstColor.COMMENT
            SemanticTokenTypes.String -> TypstColor.STRING
            SemanticTokenTypes.Keyword -> TypstColor.KEYWORD
            SemanticTokenTypes.Operator -> TypstColor.OPERATOR
            SemanticTokenTypes.Number -> TypstColor.NUMBER
            SemanticTokenTypes.Decorator -> TypstColor.DECORATOR
            SemanticTokenTypes.Type -> TypstColor.TYPE
            SemanticTokenTypes.Namespace -> TypstColor.MODULE
            SemanticTokenTypes.Function ->
                if (SemanticTokenModifiers.DefaultLibrary in modifiers) TypstColor.BUILTIN
                else TypstColor.FUNCTION

            BOOLEAN -> TypstColor.BOOLEAN
            PUNCTUATION -> TypstColor.PUNCTUATION
            ESCAPE -> TypstColor.ESCAPE
            LINK -> TypstColor.LINK
            LABEL -> TypstColor.LABEL
            REFERENCE -> TypstColor.REFERENCE
            HEADING -> TypstColor.HEADING
            LIST_MARKER -> TypstColor.LIST_MARKER
            LIST_TERM -> TypstColor.LIST_TERM
            MATH_DELIMITER -> TypstColor.MATH_DELIMITER
            INTERPOLATED ->
                if (MATH in modifiers) TypstColor.MATH_VARIABLE else TypstColor.VARIABLE

            SYNTAX_ERROR -> TypstColor.SYNTAX_ERROR
            RAW -> TypstColor.RAW
            else -> null
        }

    private fun colorOfModifiers(modifiers: List<String>): TypstColor? = when {
        STRONG in modifiers && EMPHASIS in modifiers -> TypstColor.STRONG_EMPHASIS
        STRONG in modifiers -> TypstColor.STRONG
        EMPHASIS in modifiers -> TypstColor.EMPHASIS
        MATH in modifiers -> TypstColor.MATH
        else -> null
    }
}
