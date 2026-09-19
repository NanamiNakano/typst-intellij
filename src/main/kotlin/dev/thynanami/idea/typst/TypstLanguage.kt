package dev.thynanami.idea.typst

import com.intellij.lang.Language

object TypstLanguage : Language("Typst")

object TypstCodeLanguage : Language("TypstCode") {
    override fun getDisplayName() = "Typst (Code Mode)"
}
