package dev.thynanami.idea.typst

import dev.thynanami.idea.typst.languageserver.TinymistLanguageServer
import com.google.gson.Gson
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import org.eclipse.lsp4j.ExecuteCommandParams
import java.util.UUID

private val LOG = logger<TinymistPreviewServer>()
private val gson = Gson()

class TinymistPreviewServer(project: Project, private val filepath: String) {
  private val taskId = UUID.randomUUID().toString()
  private val previewArguments =
    listOf("--task-id", taskId, "--data-plane-host", LOOPBACK_DATA_PLANE_HOST, filepath)
  private val languageServer = TinymistLanguageServer.getInstance(project)

  fun start(onAddress: (String) -> Unit, onFailure: (Throwable) -> Unit) {
    languageServer.launch {
      runCatching { startPreview() }.fold(onAddress, onFailure)
    }
  }

  fun stop() {
    languageServer.launch {
      languageServer.running()?.execute(KILL_PREVIEW_COMMAND, taskId)
    }
  }

  private suspend fun startPreview(): String {
    val server = languageServer.awaitRunning()
      ?: error("Tinymist language server is not running")
    val response = server.execute(START_PREVIEW_COMMAND, previewArguments)
    val address = gson.toJsonTree(response).asJsonObject[STATIC_SERVER_ADDRESS].asString

    LOG.info("Tinymist preview for $filepath is served at $address")
    return "http://$address"
  }

  private suspend fun LspClient.execute(command: String, argument: Any): Any? = sendRequest {
    it.workspaceService.executeCommand(ExecuteCommandParams(command, listOf(argument)))
  }

  private companion object {
    const val START_PREVIEW_COMMAND = "tinymist.doStartPreview"
    const val KILL_PREVIEW_COMMAND = "tinymist.doKillPreview"
    const val STATIC_SERVER_ADDRESS = "staticServerAddr"
    const val LOOPBACK_DATA_PLANE_HOST = "127.0.0.1:0"
  }
}
