package dev.thynanami.idea.typst

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.vfs.VirtualFile

sealed class TypstFileTypeBase(language: Language, val textMateBundleName: String) :
    LanguageFileType(language) {
    override fun getIcon() = TypstIcons.TYPST
}

object TypstFileType : TypstFileTypeBase(TypstLanguage, "typst") {
    override fun getName() = "Typst"
    override fun getDescription() = "Typst document"
    override fun getDefaultExtension() = "typ"
}

object TypstCodeFileType : TypstFileTypeBase(TypstCodeLanguage, "typst-code") {
    override fun getName() = "Typst (Code Mode)"
    override fun getDescription() = "Typst code module"
    override fun getDefaultExtension() = "typc"
}

fun VirtualFile.isTypstFile(): Boolean = fileType is TypstFileTypeBase

fun VirtualFile.isTypstMarkupFile(): Boolean = fileType == TypstFileType
