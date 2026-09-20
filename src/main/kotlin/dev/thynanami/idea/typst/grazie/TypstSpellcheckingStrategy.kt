package dev.thynanami.idea.typst.grazie

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.registry.Registry
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.spellchecker.inspections.PlainTextSplitter
import com.intellij.spellchecker.inspections.SpellCheckingInspection.SpellCheckingScope
import com.intellij.spellchecker.inspections.Splitter
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.TokenConsumer
import com.intellij.spellchecker.tokenizer.Tokenizer
import com.intellij.util.Consumer

private val ALL_SCOPES = SpellCheckingScope.entries.toSet()

class TypstSpellcheckingStrategy : SpellcheckingStrategy(), DumbAware {
    override fun useTextLevelSpellchecking(): Boolean = Registry.`is`("spellchecker.grazie.enabled", false)

    override fun getTokenizer(element: PsiElement): Tokenizer<*> = getTokenizer(element, ALL_SCOPES)

    override fun getTokenizer(element: PsiElement, scope: Set<SpellCheckingScope>): Tokenizer<*> =
        if (element is PsiFile || element.firstChild != null || useTextLevelSpellchecking()) EMPTY_TOKENIZER
        else TypstTextTokenizer(scope)

    // The flat PSI leaf contains all domains. Filter the extracted regions, not the entire leaf.
    override fun elementFitsScope(element: PsiElement, scope: Set<SpellCheckingScope>): Boolean = scope.isNotEmpty()
}

private class TypstTextTokenizer(private val scope: Set<SpellCheckingScope>) : Tokenizer<PsiElement>() {
    override fun tokenize(element: PsiElement, consumer: TokenConsumer) {
        val text = element.text
        for ((range, domain, exclusions) in typstTextRegions(element)) {
            val regionScope = when (domain) {
                TypstTextDomain.PROSE -> SpellCheckingScope.Code
                TypstTextDomain.COMMENT -> SpellCheckingScope.Comments
                TypstTextDomain.STRING -> SpellCheckingScope.Literals
            }
            if (regionScope !in scope) continue
            consumer.consumeToken(element, text, false, 0, range, TypstTextSplitter(exclusions))
        }
    }
}

private class TypstTextSplitter(private val exclusions: List<TypstTextExclusion>) : Splitter {
    override fun split(text: String?, range: TextRange, consumer: Consumer<TextRange>) {
        PlainTextSplitter.getInstance().split(text, range) { word ->
            if (exclusions.none { exclusion ->
                    if (exclusion.kind == TypstTextExclusionKind.UNKNOWN) exclusion.range.intersects(word)
                    else exclusion.range.intersectsStrict(word)
                }) {
                consumer.consume(word)
            }
        }
    }
}
