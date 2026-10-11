package dev.thynanami.idea.typst.lsp

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResult
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementDecorator
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.codeInsight.lookup.LookupElementRenderer
import com.intellij.codeInsight.lookup.SuspendingLookupElementRenderer
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Key
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind

internal val tinymistCompletionItemKey = Key.create<TinymistCompletionItem>("typst.completion.item")

internal class TinymistCompletionItem(val source: TypstCompletionSupport, val item: CompletionItem)

class TypstCompletionContributor : CompletionContributor(), DumbAware {
    override fun fillCompletionVariants(parameters: CompletionParameters, result: CompletionResultSet) {
        val completions = result.runRemainingContributors(parameters, false)
        val references = completions.filter {
            it.lookupElement.getUserData(tinymistCompletionItemKey)?.item?.kind == CompletionItemKind.Reference
        }.groupBy { it.lookupElement.getUserData(tinymistCompletionItemKey)!!.item.label }
        val aliases = mutableMapOf<CompletionResult, MutableSet<String>>()
        val removed = mutableSetOf<CompletionResult>()

        for (completion in completions) {
            val candidate = completion.lookupElement.getUserData(tinymistCompletionItemKey) ?: continue
            if (candidate.item.kind != CompletionItemKind.Constant) continue
            val key = candidate.item.labelDetails?.description ?: continue
            val reference = references[key]?.singleOrNull {
                val other = it.lookupElement.getUserData(tinymistCompletionItemKey)!!
                candidate.source === other.source && isBibliographyAlias(candidate.item, other.item)
            } ?: continue
            // The title item already matches its title AND insertion text, and carries
            // Tinymist's Constant icon. Keep that native LSP item, not the key-only one.
            aliases.getOrPut(completion) { mutableSetOf() }.addAll(reference.lookupElement.allLookupStrings)
            removed.add(reference)
        }

        for (completion in completions) {
            if (completion in removed) continue
            val lookupStrings = aliases[completion]
            if (lookupStrings == null) {
                result.passResult(completion)
            } else {
                // Relax matching without changing the case-sensitive citation key on insertion.
                result.withPrefixMatcher(completion.prefixMatcher)
                    .caseInsensitive()
                    .withRelevanceSorter(completion.sorter)
                    .addElement(TypstBibliographyLookupElement(completion.lookupElement, lookupStrings))
            }
        }
    }
}

// Tinymist's label_completions_ clones Reference(key, title) as Constant(title, key).
// Merge only this actual pair, with identical edits and non-presentation metadata.
// sortText may differ: Tinymist assigns each candidate its own index in #cite/#ref.
// Function/UFCS variants also clone items, but change insertion and must stay separate.
@Suppress("DEPRECATION")
private fun isBibliographyAlias(title: CompletionItem, reference: CompletionItem): Boolean =
    title.label.isNotBlank() && reference.label.isNotBlank() &&
        title.label == reference.labelDetails?.description &&
        title.filterText == title.label && reference.filterText == reference.label &&
        title.detail == title.label && title.detail == reference.detail &&
        title.labelDetails?.detail == reference.labelDetails?.detail &&
        title.textEdit != null && title.textEdit == reference.textEdit &&
        title.additionalTextEdits == reference.additionalTextEdits &&
        title.insertText == reference.insertText && title.textEditText == reference.textEditText &&
        title.insertTextFormat == reference.insertTextFormat && title.insertTextMode == reference.insertTextMode &&
        title.command == reference.command && title.data == reference.data &&
        title.documentation == reference.documentation &&
        title.preselect == reference.preselect && title.commitCharacters == reference.commitCharacters &&
        title.tags == reference.tags && title.deprecated == reference.deprecated

private class TypstBibliographyLookupElement(delegate: LookupElement, aliases: Set<String>) :
    LookupElementDecorator<LookupElement>(delegate) {
    private val lookupStrings = delegate.allLookupStrings + aliases

    override fun getAllLookupStrings(): Set<String> = lookupStrings

    override fun renderElement(presentation: LookupElementPresentation) {
        super.renderElement(presentation)
        showKeyFirst(presentation)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getExpensiveRenderer(): LookupElementRenderer<TypstBibliographyLookupElement>? =
        (delegate.expensiveRenderer as? LookupElementRenderer<LookupElement>)?.let(::TypstBibliographyRenderer)

    fun showKeyFirst(presentation: LookupElementPresentation) {
        val title = presentation.itemText
        presentation.itemText = presentation.typeText
        presentation.typeText = title
    }
}

private class TypstBibliographyRenderer(private val renderer: LookupElementRenderer<LookupElement>) :
    SuspendingLookupElementRenderer<TypstBibliographyLookupElement>() {
    override suspend fun renderElementSuspending(
        element: TypstBibliographyLookupElement,
        presentation: LookupElementPresentation,
    ) {
        if (renderer is SuspendingLookupElementRenderer<LookupElement>) {
            renderer.renderElementSuspending(element.delegate, presentation)
        } else {
            renderer.renderElement(element.delegate, presentation)
        }
        element.showKeyFirst(presentation)
    }
}
