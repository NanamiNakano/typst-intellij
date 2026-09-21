package dev.thynanami.idea.typst.editor

import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.textmate.TextMateService
import org.jetbrains.plugins.textmate.language.preferences.Preferences
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateElementType
import org.jetbrains.plugins.textmate.language.syntax.lexer.TextMateScope

internal fun Editor.typstScopeAt(offset: Int): TextMateScope? {
    if (offset !in 0 until document.textLength) return null
    val iterator = highlighter.createIterator(offset)
    return if (iterator.atEnd()) null else (iterator.tokenType as? TextMateElementType)?.scope
}

internal fun TextMateScope?.typstScopeNames(): Sequence<String> =
    generateSequence(this) { it.parent }.flatMap { it.scopeName?.split(' ').orEmpty().asSequence() }

internal fun Editor.typstPreferences(file: PsiFile, offset: Int): List<Preferences> {
    val textMate = TextMateService.getInstance()
    val scope = typstScopeAt(offset) ?: typstScopeAt(offset - 1)
        ?: textMate.getLanguageDescriptorByFileName(file.name)?.let { TextMateScope(it.rootScopeName, null) }
        ?: return emptyList()
    return textMate.preferenceRegistry.getPreferences(scope)
}
