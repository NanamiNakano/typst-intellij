package dev.thynanami.idea.typst.language.filetype

import dev.thynanami.idea.typst.TypstIcons
import dev.thynanami.idea.typst.language.language.TypstLanguage
import com.intellij.openapi.fileTypes.LanguageFileType
import org.jetbrains.annotations.NotNull
import javax.swing.Icon

class TypstFileType private constructor() : LanguageFileType(TypstLanguage.INSTANCE) {
  @NotNull
  override fun getName(): String {
    return "Typst"
  }

  @NotNull
  override fun getDescription(): String {
    return "Typst file"
  }

  @NotNull
  override fun getDefaultExtension(): String {
    return "typ"
  }

  override fun getIcon(): Icon {
    return TypstIcons.TYPST_FILE
  }

  companion object {
    val INSTANCE: TypstFileType = TypstFileType()
  }
}
