package dev.thynanami.idea.typst.config

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm
import com.intellij.util.concurrency.AppExecutorUtil
import dev.thynanami.idea.typst.lsp.TinymistBinary
import dev.thynanami.idea.typst.lsp.TinymistCodeStylePreviewSession
import dev.thynanami.idea.typst.lsp.TinymistFormattingOptions
import java.nio.file.Path
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

internal class TypstCodeStylePreviewController(
    private val pluginPath: Path,
    parent: Disposable,
    private val displayText: (String) -> Unit,
    private val displayError: (String?) -> Unit,
) : Disposable {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private var lastSnapshot: TypstPreviewSnapshot? = null
    private var currentWork: TypstPreviewWork? = null
    private var future: Future<*>? = null
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
        val modality = ModalityState.current()
        val work = TypstPreviewWork(pluginPath, snapshot) { completed, result, error ->
            ApplicationManager.getApplication().invokeLater({
                if (!disposed && currentWork === completed) {
                    currentWork = null
                    future = null
                    if (error == null) {
                        displayError(null)
                        displayText(result ?: snapshot.source)
                    } else {
                        lastSnapshot = null
                        logger<TypstCodeStylePreviewController>().warn("Could not format Typst code style preview", error)
                        displayError(error.message ?: "Tinymist could not format the preview.")
                    }
                }
            }, modality)
        }
        currentWork = work
        alarm.addRequest({
            if (!disposed && currentWork === work) {
                future = AppExecutorUtil.getAppExecutorService().submit(work)
            }
        }, 300, modality)
    }

    fun cancel() {
        alarm.cancelAllRequests()
        lastSnapshot = null
        currentWork?.cancel()
        currentWork = null
        future?.cancel(true)
        future = null
    }

    override fun dispose() {
        disposed = true
        cancel()
    }
}

private data class TypstPreviewSnapshot(val source: String, val options: TinymistFormattingOptions)

private class TypstPreviewWork(
    private val pluginPath: Path,
    private val snapshot: TypstPreviewSnapshot,
    private val completed: (TypstPreviewWork, String?, Exception?) -> Unit,
) : Runnable {
    private val cancelled = AtomicBoolean()

    @Volatile
    private var session: TinymistCodeStylePreviewSession? = null

    override fun run() {
        if (cancelled.get()) return
        try {
            val currentSession = TinymistCodeStylePreviewSession(
                TinymistBinary.resolve(pluginPath), snapshot.source, snapshot.options,
            )
            session = currentSession
            if (cancelled.get()) {
                currentSession.cancel()
                return
            }
            val result = currentSession.format()
            if (!cancelled.get()) completed(this, result, null)
        } catch (error: Exception) {
            if (!cancelled.get()) completed(this, null, error)
        } finally {
            session = null
        }
    }

    fun cancel() {
        cancelled.set(true)
        session?.cancel()
    }
}
