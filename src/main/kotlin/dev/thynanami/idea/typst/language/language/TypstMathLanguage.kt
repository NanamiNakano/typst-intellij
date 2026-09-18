package dev.thynanami.idea.typst.language.language

import com.intellij.lang.Language

class TypstMathLanguage : Language("TypstMath") {
  companion object {
    @JvmStatic
    val INSTANCE: TypstMathLanguage = TypstMathLanguage()
  }
}
