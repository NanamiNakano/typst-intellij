package dev.thynanami.idea.typst.lsp

import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.platform.lsp.util.applyTextEdits
import com.intellij.util.concurrency.AppExecutorUtil
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
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class TinymistCodeStylePreviewSession(
    private val binaryPath: Path,
    private val text: String,
    private val options: TinymistFormattingOptions,
) {
    private val cancelled = AtomicBoolean()
    private val process = AtomicReference<Process?>()
    private val listening = AtomicReference<Future<Void>?>()
    private val request = AtomicReference<CompletableFuture<*>?>()

    fun format(): String? {
        checkCancelled()
        val timeout = AppExecutorUtil.getAppScheduledExecutorService().schedule(::cancel, 12, TimeUnit.SECONDS)
        var server: LanguageServer? = null
        var initialized = false
        try {
            val directory = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
            val started = ProcessBuilder(binaryPath.toString())
                .directory(directory.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            process.set(started)
            checkCancelled()

            val launcher = Launcher.Builder<LanguageServer>()
                .setLocalService(TinymistPreviewLanguageClient())
                .setRemoteInterface(LanguageServer::class.java)
                .setInput(started.inputStream)
                .setOutput(started.outputStream)
                .setExecutorService(AppExecutorUtil.getAppExecutorService())
                .create()
            listening.set(launcher.startListening())
            val remote = launcher.remoteProxy
            server = remote
            val result = await(remote.initialize(InitializeParams().apply {
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
            }))
            initialized = true
            check(result.capabilities.positionEncoding.let { it == null || it == PositionEncodingKind.UTF16 }) {
                "Tinymist selected an unsupported preview position encoding"
            }
            checkCancelled()
            remote.initialized(InitializedParams())

            // didOpen supplies the content; the isolated preview never creates a source file.
            val uri = directory.resolve("typst-code-style-preview-${UUID.randomUUID()}.typ").toUri().toString()
            remote.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "typst", 1, text)))
            val edits = await(remote.textDocumentService.formatting(
                DocumentFormattingParams(TextDocumentIdentifier(uri), FormattingOptions(options.indentSize, true))
            ))
            checkCancelled()
            if (edits.isNullOrEmpty()) return null

            val document = DocumentImpl(text, false, true)
            check(applyTextEdits(document, edits)) { "Tinymist returned invalid preview formatting edits" }
            return document.text.takeUnless { it == text }
        } finally {
            try {
                stopProcess(server, initialized)
            } finally {
                timeout.cancel(false)
            }
        }
    }

    fun cancel() {
        cancelled.set(true)
        process.get()?.destroyForcibly()
        request.get()?.cancel(true)
        listening.get()?.cancel(true)
    }

    private fun <T> await(future: CompletableFuture<T>): T {
        request.set(future)
        try {
            checkCancelled()
            return future.get(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw CancellationException("Typst code style preview cancelled")
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            throw timeout
        } finally {
            request.compareAndSet(future, null)
        }
    }

    private fun checkCancelled() {
        if (cancelled.get() || Thread.currentThread().isInterrupted) {
            throw CancellationException("Typst code style preview cancelled")
        }
    }

    private fun stopProcess(server: LanguageServer?, initialized: Boolean) {
        val started = process.get()
        if (!cancelled.get() && initialized && server != null && started?.isAlive == true) {
            try {
                server.shutdown().get(1, TimeUnit.SECONDS)
                server.exit()
                started.waitFor(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                // A failed or unresponsive preview server is terminated below.
            }
        }
        if (started?.isAlive == true) started.destroyForcibly()
        listening.getAndSet(null)?.cancel(true)
        started?.let {
            runCatching { it.outputStream.close() }
            runCatching { it.inputStream.close() }
            runCatching { it.errorStream.close() }
        }
        process.set(null)
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
