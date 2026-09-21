package dev.thynanami.idea.typst.lsp

import com.google.gson.JsonObject
import com.intellij.psi.codeStyle.CodeStyleSettings
import dev.thynanami.idea.typst.TypstLanguage
import dev.thynanami.idea.typst.config.TypstCodeStyleSettings
import dev.thynanami.idea.typst.config.TypstFormatter

internal data class TinymistFormattingOptions(
    val formatter: TypstFormatter,
    val indentSize: Int,
    val printWidth: Int,
    val proseWrap: Boolean,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("formatterMode", formatter.toString())
        addProperty("formatterIndentSize", indentSize)
        addProperty("formatterPrintWidth", printWidth)
        addProperty("formatterProseWrap", proseWrap)
    }
}

internal fun tinymistFormattingOptions(
    settings: CodeStyleSettings,
    formatter: TypstFormatter,
): TinymistFormattingOptions = TinymistFormattingOptions(
    formatter = formatter,
    indentSize = settings.getLanguageIndentOptions(TypstLanguage).INDENT_SIZE,
    printWidth = settings.getRightMargin(TypstLanguage),
    proseWrap = settings.getCustomSettings(TypstCodeStyleSettings::class.java).PROSE_WRAP,
)
