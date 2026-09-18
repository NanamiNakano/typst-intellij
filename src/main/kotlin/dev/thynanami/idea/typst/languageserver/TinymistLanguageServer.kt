package dev.thynanami.idea.typst.languageserver

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object TinymistLanguageServer {
  private val startupPollDelay = 300.milliseconds
  private val startupTimeout = 15.seconds
  private val providerClass = TypstLspServerSupportProvider::class.java

  fun restart(project: Project) {
    AppExecutorUtil.getAppExecutorService().execute {
      runBlocking {
        LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded(providerClass)
        awaitRunning(project)
        withContext(Dispatchers.EDT) {
          DaemonCodeAnalyzer.getInstance(project).restart("Tinymist language server restarted")
        }
      }
    }
  }

  suspend fun awaitRunning(project: Project): LspClient? = withTimeoutOrNull(startupTimeout) {
    var client = running(project)
    while (client == null) {
      delay(startupPollDelay)
      client = running(project)
    }
    client
  }

  fun running(project: Project): LspClient? = LspClientManager.getInstance(project)
    .getClients(providerClass)
    .firstOrNull { it.state == LspServerState.Running }
}
