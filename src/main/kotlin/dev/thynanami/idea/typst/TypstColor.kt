package dev.thynanami.idea.typst

import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.options.colors.AttributesDescriptor

enum class TypstColor(private val displayName: String, inheritedFrom: TextAttributesKey? = null) {
    COMMENT("Code//Comment", DefaultLanguageHighlighterColors.LINE_COMMENT),
    KEYWORD("Code//Keyword", DefaultLanguageHighlighterColors.KEYWORD),
    VARIABLE("Code//Variable", DefaultLanguageHighlighterColors.LOCAL_VARIABLE),
    RAW("Markup//Raw text"),
    FUNCTION("Code//Function", DefaultLanguageHighlighterColors.FUNCTION_CALL),
    BUILTIN("Code//Standard library definition", DefaultLanguageHighlighterColors.PREDEFINED_SYMBOL),
    MODULE("Code//Module", DefaultLanguageHighlighterColors.CLASS_REFERENCE),
    TYPE("Code//Type", DefaultLanguageHighlighterColors.CLASS_NAME),
    DECORATOR("Code//Decorator", DefaultLanguageHighlighterColors.METADATA),
    STRING("Code//String", DefaultLanguageHighlighterColors.STRING),
    ESCAPE("Code//Escape sequence", DefaultLanguageHighlighterColors.VALID_STRING_ESCAPE),
    NUMBER("Code//Number", DefaultLanguageHighlighterColors.NUMBER),
    BOOLEAN("Code//Boolean", DefaultLanguageHighlighterColors.KEYWORD),
    OPERATOR("Code//Operator", DefaultLanguageHighlighterColors.OPERATION_SIGN),
    PUNCTUATION("Code//Punctuation", DefaultLanguageHighlighterColors.BRACES),
    HEADING("Markup//Heading", DefaultLanguageHighlighterColors.MARKUP_TAG),
    STRONG("Markup//Strong"),
    EMPHASIS("Markup//Emphasis"),
    STRONG_EMPHASIS("Markup//Strong emphasis"),
    LIST_MARKER("Markup//List marker", DefaultLanguageHighlighterColors.MARKUP_TAG),
    LIST_TERM("Markup//Term list term", DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE),
    LABEL("Markup//Label", DefaultLanguageHighlighterColors.MARKUP_ATTRIBUTE),
    REFERENCE("Markup//Reference", DefaultLanguageHighlighterColors.MARKUP_ENTITY),
    LINK("Markup//Link", CodeInsightColors.HYPERLINK_ATTRIBUTES),
    MATH_DELIMITER("Math//Delimiter", DefaultLanguageHighlighterColors.STRING),
    MATH_VARIABLE("Math//Variable"),
    MATH("Math//Content"),
    SYNTAX_ERROR("Syntax error", HighlighterColors.BAD_CHARACTER);

    val attributes: TextAttributesKey = inheritedFrom
        ?.let { createTextAttributesKey("TYPST_$name", it) }
        ?: createTextAttributesKey("TYPST_$name")

    val demoTag: String get() = name.lowercase()

    val descriptor: AttributesDescriptor get() = AttributesDescriptor(displayName, attributes)
}
