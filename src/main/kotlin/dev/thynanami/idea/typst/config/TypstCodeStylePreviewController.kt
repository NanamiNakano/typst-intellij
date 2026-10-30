package dev.thynanami.idea.typst.config

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.Disposer
import dev.thynanami.idea.typst.lsp.TinymistBinary
import dev.thynanami.idea.typst.lsp.TinymistCodeStylePreviewSession
import dev.thynanami.idea.typst.lsp.TinymistFormattingOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

internal class TypstCodeStylePreviewController(
    private val pluginPath: Path,
    parent: Disposable,
    private val displayText: (String) -> Unit,
    private val displayError: (String?) -> Unit,
) : Disposable {
    private var lastSnapshot: TypstPreviewSnapshot? = null
    private var work: Job? = null
    private var disposed = false

    init {
        Disposer.register(parent, this)
    }

    fun request(source: String, options: TinymistFormattingOptions) {
        if (disposed) return
        val snapshot = TypstPreviewSnapshot(source, options)
        if (snapshot == lastSnapshot) return

        cancel()
        lastSnapshot = snapshot
        displayError(null)
        work = service<TypstCodeStylePreviewService>().launch(ModalityState.current()) {
            delay(300.milliseconds)
            try {
                val result = withContext(Dispatchers.IO) {
                    TinymistCodeStylePreviewSession(
                        TinymistBinary.resolve(pluginPath), snapshot.source, snapshot.options,
                    ).format()
                }
                displayError(null)
                displayText(result ?: snapshot.source)
            } catch (error: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                failed(error)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cancelled: ProcessCanceledException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                failed(error)
            }
        }
    }

    private fun failed(error: Exception) {
        lastSnapshot = null
        logger<TypstCodeStylePreviewController>().warn("Could not format Typst code style preview", error)
        displayError(error.message ?: "Tinymist could not format the preview.")
    }

    fun cancel() {
        lastSnapshot = null
        work?.cancel()
        work = null
    }

    override fun dispose() {
        disposed = true
        cancel()
    }
}

private data class TypstPreviewSnapshot(val source: String, val options: TinymistFormattingOptions)

@Service(Service.Level.APP)
internal class TypstCodeStylePreviewService(private val scope: CoroutineScope) {
    // The platform cancels and awaits this work on plugin unload; the panel can cancel it sooner.
    fun launch(modality: ModalityState, task: suspend () -> Unit): Job =
        scope.launch(Dispatchers.EDT + modality.asContextElement()) { task() }
}
