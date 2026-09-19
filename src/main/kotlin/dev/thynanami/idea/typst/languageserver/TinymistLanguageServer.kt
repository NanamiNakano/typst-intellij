package dev.thynanami.idea.typst.languageserver

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val startupPollDelay = 300.milliseconds
private val startupTimeout = 15.seconds
private val providerClass = TypstLspServerSupportProvider::class.java

@Service(Service.Level.PROJECT)
class TinymistLanguageServer(private val project: Project, private val scope: CoroutineScope) {
    fun launch(task: suspend () -> Unit): Job = scope.launch { task() }

    fun restart(): Job = launch {
        LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded(providerClass)
        awaitRunning()
        withContext(Dispatchers.EDT) {
            DaemonCodeAnalyzer.getInstance(project).restart("Tinymist language server restarted")
        }
    }

    suspend fun awaitRunning(): LspClient? = withTimeoutOrNull(startupTimeout) {
        var client = running()
        while (client == null) {
            delay(startupPollDelay)
            client = running()
        }
        client
    }

    fun running(): LspClient? = LspClientManager.getInstance(project)
        .getClients(providerClass)
        .firstOrNull { it.state == LspServerState.Running }

    companion object {
        fun getInstance(project: Project): TinymistLanguageServer = project.service()
    }
}
