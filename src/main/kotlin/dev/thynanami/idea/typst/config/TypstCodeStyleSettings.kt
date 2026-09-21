package dev.thynanami.idea.typst.config

import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CustomCodeStyleSettings

class TypstCodeStyleSettings(settings: CodeStyleSettings) : CustomCodeStyleSettings("TypstCodeStyleSettings", settings) {
    @JvmField
    @Suppress("PropertyName")
    var PROSE_WRAP = false
}
