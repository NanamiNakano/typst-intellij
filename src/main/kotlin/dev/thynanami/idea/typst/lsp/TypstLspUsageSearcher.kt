package dev.thynanami.idea.typst.lsp

import com.intellij.find.usages.api.PsiUsage
import com.intellij.find.usages.api.Usage
import com.intellij.find.usages.api.UsageSearchParameters
import com.intellij.find.usages.api.UsageSearcher
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiManager
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.PsiSearchScopeUtil
import com.intellij.psi.search.SearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.util.AbstractQuery
import com.intellij.util.Processor
import com.intellij.util.Query
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import kotlin.jvm.JvmDefaultWithoutCompatibility

// Inherit platform defaults without generating bridges into its restricted API.
@JvmDefaultWithoutCompatibility
class TypstLspUsageSearcher : UsageSearcher {
    override fun collectSearchRequest(parameters: UsageSearchParameters): Query<Usage>? {
        val symbol = parameters.target as? TypstLspSymbol ?: return null
        return TypstLspReferencesQuery(symbol, parameters.project, parameters.searchScope)
    }
}

private class TypstLspReferencesQuery(
    symbol: TypstLspSymbol,
    private val project: Project,
    private val scope: SearchScope,
) : AbstractQuery<Usage>() {
    private val pointer = symbol.createPointer()

    override fun processResults(consumer: Processor<in Usage>): Boolean {
        ProgressManager.checkCanceled()
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread || application.isReadAccessAllowed || application.isWriteAccessAllowed) return true

        val (client, params, modificationCount) =
            ReadAction.nonBlocking<Triple<LspClient, ReferenceParams, Long>?> {
                if (project.isDisposed) return@nonBlocking null
                val symbol = pointer.dereference() ?: return@nonBlocking null
                val client = symbol.client
                if (project.service<TinymistLanguageServer>().running() !== client) return@nonBlocking null
                val file = symbol.file.virtualFile ?: return@nonBlocking null
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@nonBlocking null
                if (symbol.range.endOffset > document.textLength) return@nonBlocking null
                val params = ReferenceParams(
                    client.getDocumentIdentifier(file),
                    getLsp4jPosition(document, symbol.range.startOffset),
                    ReferenceContext(false),
                )
                Triple(client, params, PsiModificationTracker.getInstance(project).modificationCount)
            }.executeSynchronously() ?: return true

        val locations = client.sendRequestSync(60_000) { it.textDocumentService.references(params) } ?: return true
        val seen = mutableSetOf<Pair<VirtualFile, TextRange>>()
        for (location in locations) {
            ProgressManager.checkCanceled()
            val proceed = ReadAction.nonBlocking<Boolean> {
                if (project.isDisposed || project.service<TinymistLanguageServer>().running() !== client ||
                    PsiModificationTracker.getInstance(project).modificationCount != modificationCount
                ) return@nonBlocking false
                val symbol = pointer.dereference() ?: return@nonBlocking false
                val file = client.descriptor.findFileByUri(location.uri) ?: return@nonBlocking true
                val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@nonBlocking true
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@nonBlocking true
                val range = getRangeInDocument(document, location.range) ?: return@nonBlocking true
                // Tinymist includes declarations even when includeDeclaration is false.
                if (symbol.file.virtualFile == file && symbol.range.contains(range)) return@nonBlocking true
                val inScope = if (scope is LocalSearchScope) scope.containsRange(psiFile, range)
                else PsiSearchScopeUtil.isInScope(scope, psiFile)
                if (!inScope || !seen.add(file to range)) return@nonBlocking true
                consumer.process(PsiUsage.textUsage(psiFile, range))
            }.executeSynchronously()
            if (!proceed) return false
        }
        return true
    }
}
