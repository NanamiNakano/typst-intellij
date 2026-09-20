package dev.thynanami.idea.typst.psi

import com.intellij.psi.tree.IElementType
import com.intellij.psi.tree.IFileElementType
import dev.thynanami.idea.typst.TypstCodeLanguage
import dev.thynanami.idea.typst.TypstLanguage

internal val TYPST_CONTENT = IElementType("TYPST_CONTENT", TypstLanguage)
internal val TYPST_FILE = IFileElementType(TypstLanguage)
internal val TYPST_CODE_CONTENT = IElementType("TYPST_CODE_CONTENT", TypstCodeLanguage)
internal val TYPST_CODE_FILE = IFileElementType(TypstCodeLanguage)
