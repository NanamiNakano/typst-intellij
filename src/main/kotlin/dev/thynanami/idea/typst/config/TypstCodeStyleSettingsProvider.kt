package dev.thynanami.idea.typst.config

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.IndentOptionsEditor
import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsCustomizable
import com.intellij.psi.codeStyle.CommonCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import dev.thynanami.idea.typst.TypstLanguage
import java.nio.file.Path

internal val typstCodeStyleSample = """
    #let greeting(name) = {
      let message = "Hello, " + name
      text(weight: "bold", message)
    }

    #align(center)[
      #greeting("Typst")
    ]

    #let palette = (primary: rgb("#336699"), secondary: rgb("#669933"), accent: rgb("#cc6633"), background: rgb("#f5f5f5"))

    Typst combines expressive markup with a flexible scripting language. This paragraph demonstrates how prose wrapping changes the source layout while preserving the words and the appearance of the typeset document.
""".trimIndent()

class TypstCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider(), PluginAware {
    private lateinit var pluginPath: Path

    override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
        pluginPath = pluginDescriptor.pluginPath
    }

    override fun getLanguage() = TypstLanguage

    override fun createCustomSettings(settings: CodeStyleSettings) = TypstCodeStyleSettings(settings)

    override fun customizeDefaults(
        commonSettings: CommonCodeStyleSettings,
        indentOptions: CommonCodeStyleSettings.IndentOptions,
    ) {
        commonSettings.RIGHT_MARGIN = 120
        indentOptions.INDENT_SIZE = 2
        indentOptions.CONTINUATION_INDENT_SIZE = 2
        indentOptions.USE_TAB_CHARACTER = false
    }

    override fun customizeSettings(consumer: CodeStyleSettingsCustomizable, settingsType: SettingsType) {
        if (settingsType == SettingsType.INDENT_SETTINGS) {
            consumer.showStandardOptions("INDENT_SIZE")
        } else if (settingsType == SettingsType.WRAPPING_AND_BRACES_SETTINGS) {
            consumer.showStandardOptions("RIGHT_MARGIN")
            consumer.showCustomOption(TypstCodeStyleSettings::class.java, "PROSE_WRAP", "Wrap prose", null)
        }
    }

    override fun getIndentOptionsEditor(): IndentOptionsEditor = IndentOptionsEditor(this)

    override fun createConfigurable(settings: CodeStyleSettings, modelSettings: CodeStyleSettings): CodeStyleConfigurable =
        TypstCodeStyleConfigurable(settings, modelSettings, pluginPath)

    override fun createFileFromText(project: Project, text: String): PsiFile =
        PsiFileFactory.getInstance(project).createFileFromText("preview.typ", TypstLanguage, text, false, false)

    override fun getCodeSample(settingsType: SettingsType) = typstCodeStyleSample
}

private class TypstCodeStyleConfigurable(
    settings: CodeStyleSettings,
    modelSettings: CodeStyleSettings,
    private val pluginPath: Path,
) : CodeStyleAbstractConfigurable(settings, modelSettings, TypstLanguage.displayName) {
    override fun createPanel(settings: CodeStyleSettings) = TypstCodeStylePanel(currentSettings, settings, pluginPath)
}
