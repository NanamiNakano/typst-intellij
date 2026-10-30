package dev.thynanami.idea.typst.lsp

import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.platform.lsp.util.applyTextEdits
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.io.awaitExit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentFormattingParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.GeneralClientCapabilities
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PositionEncodingKind
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

internal class TinymistCodeStylePreviewSession(
    private val binaryPath: Path,
    private val text: String,
    private val options: TinymistFormattingOptions,
) {
    suspend fun format(): String? = withContext(Dispatchers.IO) {
        withTimeout(12.seconds) {
            currentCoroutineContext().ensureActive()
            val directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
            val process = ProcessBuilder(binaryPath.toString())
                .directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            var listening: Future<Void>? = null
            try {
                currentCoroutineContext().ensureActive()
                val launcher = Launcher.Builder<LanguageServer>()
                    .setLocalService(TinymistPreviewLanguageClient())
                    .setRemoteInterface(LanguageServer::class.java)
                    .setInput(process.inputStream)
                    .setOutput(process.outputStream)
                    .setExecutorService(AppExecutorUtil.getAppExecutorService())
                    .create()
                listening = launcher.startListening()
                val remote = launcher.remoteProxy
                val result = withTimeout(5.seconds) {
                    remote.initialize(InitializeParams().apply {
                        capabilities = ClientCapabilities().apply {
                            general = GeneralClientCapabilities().apply {
                                positionEncodings = listOf(PositionEncodingKind.UTF16)
                            }
                        }
                        workspaceFolders = listOf(WorkspaceFolder(directory.toUri().toString(), "Code style preview"))
                        initializationOptions = options.toJson().apply {
                            addProperty("syntaxOnly", "enable")
                            addProperty("customizedShowDocument", true)
                        }
                    }).await()
                }
                check(result.capabilities.positionEncoding.let { it == null || it == PositionEncodingKind.UTF16 }) {
                    "Tinymist selected an unsupported preview position encoding"
                }
                remote.initialized(InitializedParams())

                // didOpen supplies the content; the isolated preview never creates a source file.
                val uri = directory.resolve("typst-code-style-preview-${UUID.randomUUID()}.typ").toUri().toString()
                remote.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "typst", 1, text)))
                val edits = withTimeout(5.seconds) {
                    remote.textDocumentService.formatting(
                        DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(options.indentSize, true))
                    ).await()
                }
                val formatted = if (edits.isNullOrEmpty()) null else {
                    val document = DocumentImpl(text, false, true)
                    check(applyTextEdits(document, edits)) { "Tinymist returned invalid preview formatting edits" }
                    document.text.takeUnless { it == text }
                }
                try {
                    withTimeoutOrNull(1.seconds) {
                        remote.shutdown().await()
                        remote.exit()
                        process.awaitExit()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Cleanup below also handles a failed shutdown request.
                }
                formatted
            } finally {
                // Keep transport cleanup within the service job so unloading awaits its completion.
                withContext(NonCancellable) {
                    if (process.isAlive) process.destroyForcibly()
                    runCatching { process.outputStream.close() }
                    runCatching { process.inputStream.close() }
                    runCatching { process.errorStream.close() }
                    try {
                        withTimeoutOrNull(1.seconds) { process.awaitExit() }
                        listening?.get(1, TimeUnit.SECONDS)
                    } catch (_: Exception) {
                        listening?.cancel(true)
                    }
                }
            }
        }
    }
}

private class TinymistPreviewLanguageClient : LanguageClient {
    override fun telemetryEvent(value: Any?) = Unit

    override fun publishDiagnostics(params: PublishDiagnosticsParams) = Unit

    override fun showMessage(params: MessageParams) = Unit

    override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
        CompletableFuture.completedFuture(null)

    override fun logMessage(params: MessageParams) = Unit
}
