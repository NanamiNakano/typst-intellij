package dev.thynanami.idea.typst.language.language

import com.intellij.lang.Language

class TypstLanguage : Language("Typst") {
  companion object {
    @JvmStatic
    val INSTANCE: TypstLanguage = TypstLanguage()
  }
}
