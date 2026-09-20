package dev.thynanami.idea.typst.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.lang.Language
import com.intellij.lexer.DummyLexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiFile
import dev.thynanami.idea.typst.TypstCodeLanguage
import dev.thynanami.idea.typst.TypstLanguage
import org.jetbrains.plugins.textmate.psi.TextMateParserDefinition

class TypstParserDefinition : TextMateParserDefinition() {
    override fun createLexer(project: Project) = DummyLexer(TYPST_CONTENT)

    override fun getFileNodeType() = TYPST_FILE

    override fun createFile(viewProvider: FileViewProvider): PsiFile = TypstFile(viewProvider, TypstLanguage)
}

class TypstCodeParserDefinition : TextMateParserDefinition() {
    override fun createLexer(project: Project) = DummyLexer(TYPST_CODE_CONTENT)

    override fun getFileNodeType() = TYPST_CODE_FILE

    override fun createFile(viewProvider: FileViewProvider): PsiFile = TypstFile(viewProvider, TypstCodeLanguage)
}

private class TypstFile(viewProvider: FileViewProvider, language: Language) : PsiFileBase(viewProvider, language) {
    override fun getFileType() = viewProvider.fileType
}
