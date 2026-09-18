package dev.thynanami.idea.typst.language.language

import com.intellij.lang.Language

class TypstCodeLanguage : Language("TypstCode") {
  companion object {
    @JvmStatic
    val INSTANCE: TypstCodeLanguage = TypstCodeLanguage()
  }
}
