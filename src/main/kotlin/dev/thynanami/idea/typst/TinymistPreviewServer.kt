package dev.thynanami.idea.typst

import dev.thynanami.idea.typst.languageserver.TypstLanguageServerManager
import dev.thynanami.idea.typst.languageserver.TypstLspServerSupportProvider
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ExecuteCommandParams
import java.util.UUID
import java.util.concurrent.CompletableFuture

private val LOG = logger<TinymistPreviewServer>()
private val gson = Gson()

class TinymistPreviewServer(private val project: Project, private val filepath: String) {
  private val taskId = UUID.randomUUID().toString()
  private val previewArguments =
    listOf("--task-id", taskId, "--data-plane-host", EPHEMERAL_LOOPBACK_PORT, filepath)

  fun start(): CompletableFuture<String> = CompletableFuture.supplyAsync(
    { runBlocking { startPreview() } },
    AppExecutorUtil.getAppExecutorService(),
  )

  fun stop() {
    AppExecutorUtil.getAppExecutorService().execute {
      runCatching { runBlocking { runningLanguageServer()?.execute(KILL_PREVIEW_COMMAND, taskId) } }
        .onFailure { LOG.warn("Failed to stop tinymist preview for $filepath", it) }
    }
  }

  private suspend fun startPreview(): String {
    val languageServer = waitForLanguageServer() ?: error("Tinymist language server is not running")
    val response = languageServer.execute(START_PREVIEW_COMMAND, previewArguments)
    val address = gson.toJsonTree(response).asJsonObject[STATIC_SERVER_ADDRESS].asString

    LOG.info("Tinymist preview for $filepath is served at $address")
    return "http://$address"
  }

  private suspend fun LspClient.execute(command: String, argument: Any): Any? = sendRequest {
    it.workspaceService.executeCommand(ExecuteCommandParams(command, listOf(argument)))
  }

  private suspend fun waitForLanguageServer(): LspClient? = TypstLanguageServerManager.waitForServer(
    LspClientManager.getInstance(project),
    TypstLspServerSupportProvider::class.java,
  )

  private fun runningLanguageServer(): LspClient? = LspClientManager.getInstance(project)
    .getClients(TypstLspServerSupportProvider::class.java)
    .firstOrNull { it.state == LspServerState.Running }

  private companion object {
    const val START_PREVIEW_COMMAND = "tinymist.doStartPreview"
    const val KILL_PREVIEW_COMMAND = "tinymist.doKillPreview"
    const val STATIC_SERVER_ADDRESS = "staticServerAddr"
    const val EPHEMERAL_LOOPBACK_PORT = "127.0.0.1:0"
  }
}
