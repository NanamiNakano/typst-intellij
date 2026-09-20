package dev.thynanami.idea.typst.lsp

import com.google.gson.Gson
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import kotlinx.coroutines.CancellationException
import org.eclipse.lsp4j.ExecuteCommandParams
import java.util.UUID

private val LOG = logger<TinymistPreviewServer>()
private val gson = Gson()
private const val START_PREVIEW_COMMAND = "tinymist.doStartPreview"
private const val KILL_PREVIEW_COMMAND = "tinymist.doKillPreview"
private const val SCROLL_PREVIEW_COMMAND = "tinymist.scrollPreview"
private const val STATIC_SERVER_ADDRESS = "staticServerAddr"
private const val LOOPBACK_DATA_PLANE_HOST = "127.0.0.1:0"

class TinymistPreviewServer(private val project: Project, private val filepath: String) {
  private enum class State {
    NEW, WAITING, STARTING, RUNNING, STOPPED, DISPOSED;

    val isActive: Boolean get() = this == WAITING || this == STARTING || this == RUNNING
  }

  private val taskId = UUID.randomUUID().toString()
  private val previewArguments =
    listOf("--task-id", taskId, "--data-plane-host", LOOPBACK_DATA_PLANE_HOST, filepath)

  @Volatile
  private var state = State.NEW

  @Volatile
  private var owner: LspClient? = null

  @Volatile
  private var descriptor: TinymistLanguageServerDescriptor? = null

  val isActive: Boolean get() = state.isActive

  fun start(onAddress: (String) -> Unit, onFailure: (Throwable) -> Unit, onDisposed: () -> Unit) {
    synchronized(this) {
      if (state != State.NEW) return
      state = State.WAITING
    }

    val languageServer = project.service<TinymistLanguageServer>()
    languageServer.launch {
      try {
        val server = languageServer.awaitRunning()
          ?: error("Tinymist language server is not running")
        val serverDescriptor = server.descriptor as? TinymistLanguageServerDescriptor
          ?: error("Tinymist preview is not available")
        owner = server
        descriptor = serverDescriptor
        if (!isActive) return@launch

        val registered = serverDescriptor.registerPreview(
          taskId,
          onDisposed = { if (markDisposed().isActive) onDisposed() },
          scroll = { request ->
            if (isActive) server.execute(SCROLL_PREVIEW_COMMAND, taskId, request)
          },
        )
        if (!registered) {
          if (markDisposed().isActive) onDisposed()
          return@launch
        }
        val shouldStart = synchronized(this@TinymistPreviewServer) {
          (state == State.WAITING).also { if (it) state = State.STARTING }
        }
        if (!shouldStart) {
          serverDescriptor.unregisterPreview(taskId)
          return@launch
        }

        val response = server.execute(START_PREVIEW_COMMAND, previewArguments)
        val stateAfterStart = synchronized(this@TinymistPreviewServer) {
          if (state == State.STARTING) state = State.RUNNING
          state
        }
        if (stateAfterStart == State.STOPPED) {
          // A close during startup must wait until the server has created the task.
          kill(server)
          return@launch
        }
        if (stateAfterStart != State.RUNNING) return@launch

        val address = gson.toJsonTree(response).asJsonObject[STATIC_SERVER_ADDRESS].asString
        LOG.info("Tinymist preview for $filepath is served at $address")
        if (isActive) onAddress("http://$address")
      } catch (error: CancellationException) {
        val previousState = markDisposed()
        descriptor?.unregisterPreview(taskId)
        if (previousState.isActive) onDisposed()
        throw error
      } catch (error: Throwable) {
        val previousState = markDisposed()
        descriptor?.unregisterPreview(taskId)
        if (previousState.isActive) onFailure(error)
        if (previousState == State.RUNNING) owner?.let { server -> kill(server) }
      }
    }
  }

  fun stop() {
    val shouldKill = synchronized(this) {
      val wasRunning = state == State.RUNNING
      if (state != State.DISPOSED) state = State.STOPPED
      wasRunning
    }
    descriptor?.unregisterPreview(taskId)
    if (shouldKill && !project.isDisposed) {
      val server = owner ?: return
      project.service<TinymistLanguageServer>().launch { kill(server) }
    }
  }

  @Synchronized
  private fun markDisposed(): State = state.also { state = State.DISPOSED }

  private suspend fun kill(server: LspClient) {
    try {
      server.execute(KILL_PREVIEW_COMMAND, taskId)
    } catch (error: CancellationException) {
      throw error
    } catch (error: Exception) {
      LOG.debug("Could not stop Tinymist preview task $taskId", error)
    }
  }

  private suspend fun LspClient.execute(command: String, vararg arguments: Any): Any? = sendRequest {
    it.workspaceService.executeCommand(ExecuteCommandParams(command, arguments.toList()))
  }
}
