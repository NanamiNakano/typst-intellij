package dev.thynanami.idea.typst.lsp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import dev.thynanami.idea.typst.editor.TypstPreviewEditor
import dev.thynanami.idea.typst.editor.TypstSplitEditor
import dev.thynanami.idea.typst.isTypstFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val startupPollDelay = 300.milliseconds
private val startupTimeout = 15.seconds
private val providerClass = TypstLspServerSupportProvider::class.java

@Service(Service.Level.PROJECT)
class TinymistLanguageServer(private val project: Project, private val scope: CoroutineScope) {
    private val restartMutex = Mutex()

    fun launch(task: suspend () -> Unit): Job = scope.launch { task() }

    fun restart(): Job = launch { restartAndRestorePreviews() }

    suspend fun restartAndRestorePreviews() {
        restartMutex.withLock {
            if (project.isDisposed) return@withLock
            val manager = LspClientManager.getInstance(project)
            val previousClients = manager.getClients(providerClass).toSet()
            val hasOpenFiles = withContext(Dispatchers.EDT) { hasOpenTypstFiles() }
            if (previousClients.isEmpty() && !hasOpenFiles) return@withLock

            withContext(Dispatchers.IO) {
                manager.stopAndRestartClientsIfNeeded(providerClass)
            }
            if (!hasOpenFiles) return@withLock

            if (awaitRunning(previousClients) == null) {
                if (withContext(Dispatchers.EDT) { !hasOpenTypstFiles() }) return@withLock
                error("Tinymist did not restart within ${startupTimeout.inWholeSeconds} seconds")
            }
            withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext
                FileEditorManager.getInstance(project).allEditors
                    .filterIsInstance<TypstSplitEditor>()
                    .mapNotNull { it.previewEditor as? TypstPreviewEditor }
                    .forEach { it.restartPreview() }
                DaemonCodeAnalyzer.getInstance(project).restart("Tinymist language server restarted")
            }
        }
    }

    private fun hasOpenTypstFiles(): Boolean = !project.isDisposed &&
        FileEditorManager.getInstance(project).openFiles.any { it.isTypstFile() }

    suspend fun awaitRunning(): LspClient? = awaitRunning(emptySet())

    private suspend fun awaitRunning(excludedClients: Set<LspClient>): LspClient? = withTimeoutOrNull(startupTimeout) {
        var client = running(excludedClients)
        while (client == null) {
            delay(startupPollDelay)
            client = running(excludedClients)
        }
        client
    }

    fun running(): LspClient? = running(emptySet())

    private fun running(excludedClients: Set<LspClient>): LspClient? = LspClientManager.getInstance(project)
        .getClients(providerClass)
        .firstOrNull { it.state == LspServerState.Running && it !in excludedClients }
}
