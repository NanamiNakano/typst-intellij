package dev.thynanami.idea.typst

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.vfs.VirtualFile

object TypstFileType : LanguageFileType(TypstLanguage) {
    override fun getName() = "Typst"
    override fun getDescription() = "Typst document"
    override fun getDefaultExtension() = "typ"
    override fun getIcon() = TypstIcons.TYPST_FILE
}

fun VirtualFile.isTypstFile(): Boolean = fileType == TypstFileType
