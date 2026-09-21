package dev.thynanami.idea.typst.lsp

import com.intellij.find.usages.api.SearchTarget
import com.intellij.find.usages.api.UsageHandler
import com.intellij.model.Pointer
import com.intellij.model.psi.ImplicitReferenceProvider
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.model.psi.PsiSymbolReference
import com.intellij.navigation.NavigatableSymbol
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.navigation.NavigationRequest
import com.intellij.platform.backend.navigation.NavigationTarget
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.util.getLsp4jPosition
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.util.PsiModificationTracker
import dev.thynanami.idea.typst.isTypstFile
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.LocationLink

class TypstLspSymbolProvider : ImplicitReferenceProvider, PsiSymbolDeclarationProvider {
    override fun getImplicitReference(element: PsiElement, offsetInElement: Int): PsiSymbolReference? {
        val file = element as? PsiFile ?: return null
        val definition = definition(file, offsetInElement) ?: return null
        if (definition.declaration) return null
        val symbols = definition.targets.mapNotNull { it.dereference() }.ifEmpty { return null }
        return TypstLspReference(file, definition.range, symbols)
    }

    override fun getDeclarations(element: PsiElement, offsetInElement: Int): Collection<PsiSymbolDeclaration> {
        val file = element as? PsiFile ?: return emptyList()
        val definition = definition(file, offsetInElement) ?: return emptyList()
        if (!definition.declaration) return emptyList()
        val symbol = definition.targets.singleOrNull()?.dereference() ?: return emptyList()
        return listOf(TypstLspDeclaration(file, definition.range, symbol))
    }

    private fun definition(file: PsiFile, offset: Int): TypstLspDefinition? {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread || application.isWriteAccessAllowed) return null
        if (offset < 0 || !file.isValid || file.project.isDefault || file.project.isDisposed) return null
        val virtualFile = file.virtualFile ?: return null
        if (!virtualFile.isInLocalFileSystem || !virtualFile.isTypstFile()) return null
        return file.project.service<TypstLspNavigation>().definition(file, offset)
    }
}

@Service(Service.Level.PROJECT)
internal class TypstLspNavigation(private val project: Project) {
    @Volatile
    private var cached: CachedTypstDefinition? = null

    fun definition(file: PsiFile, offset: Int): TypstLspDefinition? {
        ProgressManager.checkCanceled()
        val client = project.service<TinymistLanguageServer>().running() ?: return null
        val capability = client.initializeResult?.capabilities?.definitionProvider ?: return null
        if (capability.isLeft && capability.left != true) return null
        val document = file.fileDocument
        if (offset > document.textLength) return null
        val stamp = document.modificationStamp
        val psiStamp = PsiModificationTracker.getInstance(project).modificationCount
        val previous = cached
        if (previous != null && previous.client === client && previous.file == file.virtualFile &&
            previous.documentStamp == stamp && previous.psiStamp == psiStamp &&
            (previous.offset == offset || previous.definition?.range?.let {
                offset >= it.startOffset && offset < it.endOffset
            } == true) &&
            (previous.definition != null || System.nanoTime() - previous.createdAt < 500_000_000L)
        ) return previous.definition

        val params = DefinitionParams(client.getDocumentIdentifier(file.virtualFile), getLsp4jPosition(document, offset))
        // Symbol providers also run during hover: use a short, cancellable wait, never a cache lock.
        val response = client.sendRequestSync(250) { it.textDocumentService.definition(params) }
        val links = response?.map({ locations -> locations.map { LocationLink(it.uri, it.range, it.range) } }, { it })
            .orEmpty()
        val origins = links.mapNotNull { it.originSelectionRange?.let { range -> getRangeInDocument(document, range) } }
        val hasRangeToRight = origins.any { it.endOffset > offset }
        var sourceRange: TextRange? = null
        val symbols = links.mapNotNull { link ->
            ProgressManager.checkCanceled()
            val origin = link.originSelectionRange?.let { getRangeInDocument(document, it) ?: return@mapNotNull null }
                ?: TextRange(offset, offset)
            if (!origin.containsOffset(offset) || (hasRangeToRight && origin.endOffset <= offset)) return@mapNotNull null
            val targetFile = client.descriptor.findFileByUri(link.targetUri) ?: return@mapNotNull null
            val targetPsi = PsiManager.getInstance(project).findFile(targetFile) ?: return@mapNotNull null
            val targetRange = getRangeInDocument(targetPsi.fileDocument, link.targetSelectionRange) ?: return@mapNotNull null
            sourceRange = sourceRange?.union(origin) ?: origin
            TypstLspSymbol(client, targetPsi, targetRange)
        }.distinct()
        val origin = sourceRange
        val definition = if (origin == null || symbols.isEmpty()) null else {
            val target = symbols.singleOrNull()
            val declaration = target?.file?.virtualFile == file.virtualFile &&
                (target.range == origin || (origin.isEmpty && target.range.containsOffset(offset)))
            TypstLspDefinition(origin, symbols.map { it.createPointer() }, declaration)
        }
        ProgressManager.checkCanceled()
        if (document.modificationStamp != stamp || PsiModificationTracker.getInstance(project).modificationCount != psiStamp ||
            client.state != LspServerState.Running
        ) return null
        cached = CachedTypstDefinition(client, file.virtualFile, stamp, psiStamp, offset, definition, System.nanoTime())
        return definition
    }
}

private data class CachedTypstDefinition(
    val client: LspClient,
    val file: VirtualFile,
    val documentStamp: Long,
    val psiStamp: Long,
    val offset: Int,
    val definition: TypstLspDefinition?,
    val createdAt: Long,
)

internal class TypstLspDefinition(
    val range: TextRange,
    val targets: List<Pointer<TypstLspSymbol>>,
    val declaration: Boolean,
)

private class TypstLspReference(
    private val file: PsiFile,
    private val range: TextRange,
    private val symbols: List<TypstLspSymbol>,
) : PsiSymbolReference {
    override fun getElement(): PsiElement = file
    override fun getRangeInElement(): TextRange = range
    override fun resolveReference(): Collection<TypstLspSymbol> = symbols
}

private class TypstLspDeclaration(
    private val file: PsiFile,
    private val range: TextRange,
    private val target: TypstLspSymbol,
) : PsiSymbolDeclaration {
    override fun getDeclaringElement(): PsiElement = file
    override fun getRangeInDeclaringElement(): TextRange = range
    override fun getSymbol(): TypstLspSymbol = target
}

internal data class TypstLspSymbol(
    val client: LspClient,
    val file: PsiFile,
    val range: TextRange,
) : NavigatableSymbol, NavigationTarget, SearchTarget {
    private val name = if (range.isEmpty) file.name else
        file.fileDocument.getText(TextRange(range.startOffset, minOf(range.endOffset, range.startOffset + 80)))

    override fun createPointer(): Pointer<TypstLspSymbol> {
        val pointer = SmartPointerManager.getInstance(file.project).createSmartPsiFileRangePointer(file, range)
        val lspClient = client
        return Pointer {
            if (lspClient.project.isDisposed || lspClient.state != LspServerState.Running) return@Pointer null
            val restoredFile = pointer.element ?: return@Pointer null
            val restoredRange = pointer.range ?: return@Pointer null
            TypstLspSymbol(lspClient, restoredFile, TextRange(restoredRange.startOffset, restoredRange.endOffset))
        }
    }

    override fun getNavigationTargets(project: Project): Collection<NavigationTarget> = listOf(this)

    override fun navigationRequest(): NavigationRequest? = NavigationRequest.sourceNavigationRequest(file, range)

    override fun computePresentation(): TargetPresentation = TargetPresentation.builder(name)
        .icon(file.fileType.icon)
        .locationText("${file.name}:${getLsp4jPosition(file.fileDocument, range.startOffset).line + 1}")
        .presentation()

    override fun presentation(): TargetPresentation = computePresentation()

    override val usageHandler: UsageHandler = UsageHandler.createEmptyUsageHandler(name)
}
