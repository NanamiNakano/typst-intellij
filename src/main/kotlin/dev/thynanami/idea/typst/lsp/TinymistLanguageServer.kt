package dev.thynanami.idea.typst.lsp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import dev.thynanami.idea.typst.editor.TypstPreviewEditor
import dev.thynanami.idea.typst.editor.TypstSplitEditor
import dev.thynanami.idea.typst.isTypstFile
import dev.thynanami.idea.typst.lsp.status.TypstStatusModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.eclipse.lsp4j.ExecuteCommandParams
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val startupPollDelay = 300.milliseconds
private val startupTimeout = 15.seconds
private val providerClass = TypstLspServerSupportProvider::class.java

@Service(Service.Level.PROJECT)
class TinymistLanguageServer(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val restartMutex = Mutex()
    private val focusRequests = Channel<Unit>(Channel.CONFLATED)
    private var selectedPath: String? = null
    private var selectionRevision = 0L
    private var focusedClient: LspClient? = null
    private var focusedRevision = -1L

    init {
        project.messageBus.connect(this).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = refreshFocus()

                override fun fileOpened(source: FileEditorManager, file: VirtualFile) = refreshFocus()

                override fun fileClosed(source: FileEditorManager, file: VirtualFile) = refreshFocus()
            },
        )
        scope.launch {
            // Keep commands ordered; a selection made during a request is processed next.
            for (ignored in focusRequests) {
                try {
                    synchronizeFocus()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger<TinymistLanguageServer>().warn("Could not update Tinymist's active document", error)
                }
            }
        }
        refreshFocus()
    }

    fun refreshFocus() {
        scope.launch(Dispatchers.EDT) {
            if (project.isDisposed) return@launch
            updateSelection()
            focusRequests.trySend(Unit)
        }
    }

    private fun selectedTypstFile(): VirtualFile? = FileEditorManager.getInstance(project)
        .selectedEditor?.file?.takeIf { it.isValid && it.isInLocalFileSystem && it.isTypstFile() }

    private fun updateSelection(): Pair<VirtualFile?, Long> {
        val file = selectedTypstFile()
        if (selectedPath != file?.path) {
            selectedPath = file?.path
            selectionRevision++
        }
        project.service<TypstStatusModel>().fileSelected(file)
        return file to selectionRevision
    }

    private suspend fun synchronizeFocus() {
        if (project.isDisposed) return
        val (file, revision) = withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext null
            updateSelection()
        } ?: return
        if (project.isDisposed) return
        // Initialization schedules another refresh; editor events never wait for startup.
        val client = running() ?: return
        val path = file?.path
        if (focusedClient === client && focusedRevision == revision) return

        focusedClient = null
        client.sendRequest { server ->
            server.workspaceService.executeCommand(ExecuteCommandParams("tinymist.focusMain", listOf(path)))
        }
        if (client.state == LspServerState.Running) {
            focusedClient = client
            focusedRevision = revision
        }
    }

    override fun dispose() = Unit

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
