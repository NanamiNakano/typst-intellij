package dev.thynanami.idea.typst.languageserver

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.EDT
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.platform.lsp.api.LspIntegrationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private val LOG = logger<TypstLanguageServerManager>()


class TypstLanguageServerManager : LanguageServerManager {
  override suspend fun initialStart(project: Project) {
    val providerClass = TypstLspServerSupportProvider::class.java
    val manager = LspClientManager.getInstance(project)
    manager.stopAndRestartClientsIfNeeded(providerClass)
    repaintOnIntialize(manager, project, providerClass)
  }

  companion object {
    private val languageServerPollDelay = 300.milliseconds
    private val languageServerPollTimeout = 15.seconds

    suspend fun repaintOnIntialize(
      manager: LspClientManager,
      project: Project,
      cls: Class<out LspIntegrationProvider>
    ) {
      waitForServer(manager, cls)
      restartCodeAnalyzer(project)
    }

    private suspend fun restartCodeAnalyzer(project: Project) {
      withContext(Dispatchers.EDT) { DaemonCodeAnalyzer.getInstance(project).restart("Tinymist language server initialized") }
    }

    suspend fun waitForServer(
      manager: LspClientManager,
      cls: Class<out LspIntegrationProvider>
    ): LspClient? = withTimeoutOrNull(languageServerPollTimeout) {
      var result: LspClient? = null
      while (result == null) {
        val servers = manager.getClients(cls)
        val targetServer =
          servers.find { server -> server.providerClass.canonicalName == cls.canonicalName }

        LOG.info("Tinymist target server: $targetServer, state: ${targetServer?.state}, class: ${cls.canonicalName}")

        if (targetServer?.state == LspServerState.Running) {
          result = targetServer
        } else {
          delay(languageServerPollDelay)
        }
      }
      result
    }
  }
}
