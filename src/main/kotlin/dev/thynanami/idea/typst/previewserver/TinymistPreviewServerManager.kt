package dev.thynanami.idea.typst.previewserver

import dev.thynanami.idea.typst.languageserver.TypstLanguageServerManager
import dev.thynanami.idea.typst.languageserver.TypstLspServerSupportProvider
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ExecuteCommandParams
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

private val LOG = logger<TinymistPreviewServerManager>()

class TinymistPreviewServerManager : PreviewServerManager {
  private data class PreviewKey(
    val project: Project,
    val filepath: String,
  )

  private data class PreviewSession(
    val taskId: String,
    val url: String,
  )

  private val sessions = ConcurrentHashMap<PreviewKey, PreviewSession>()
  private val pendingStarts = ConcurrentHashMap<PreviewKey, CompletableFuture<PreviewSession>>()

  override fun start(filepath: String, project: Project): CompletableFuture<String> {
    val key = PreviewKey(project, filepath)
    sessions[key]?.let { session ->
      return CompletableFuture.completedFuture(session.url)
    }

    val start = pendingStarts.computeIfAbsent(key, ::startAsync)
    return start.thenApply(PreviewSession::url)
  }

  override fun stop(filepath: String, project: Project) {
    val key = PreviewKey(project, filepath)
    pendingStarts.remove(key)?.cancel(false)

    val session = sessions.remove(key) ?: return
    ApplicationManager.getApplication().executeOnPooledThread {
      runCatching {
        runBlocking { killPreview(project, session.taskId) }
      }.onFailure { error ->
        LOG.warn("Failed to stop tinymist preview for $filepath", error)
      }
    }
  }

  private fun startAsync(key: PreviewKey): CompletableFuture<PreviewSession> {
    val future = CompletableFuture<PreviewSession>()
    ApplicationManager.getApplication().executeOnPooledThread {
      try {
        val session = runBlocking { startPreview(key) }
        if (future.isCancelled) {
          runBlocking { killPreview(key.project, session.taskId) }
        } else {
          sessions[key] = session
          if (!future.complete(session)) {
            sessions.remove(key, session)
            runBlocking { killPreview(key.project, session.taskId) }
          }
        }
      } catch (error: Throwable) {
        future.completeExceptionally(error)
        LOG.warn("Failed to start tinymist preview for " + key.filepath, error)
      } finally {
        pendingStarts.remove(key, future)
      }
    }
    return future
  }

  private suspend fun startPreview(key: PreviewKey): PreviewSession {
    val client = waitForServer(key.project)
      ?: error("Tinymist language server did not become ready")
    val taskId = UUID.randomUUID().toString()
    val arguments = previewArguments(key.filepath, taskId)

    LOG.info("Starting tinymist preview for " + key.filepath + ": " + arguments)
    val result: Any? = client.sendRequest {
      it.workspaceService.executeCommand(
        ExecuteCommandParams(
          START_PREVIEW_COMMAND,
          listOf(arguments),
        ),
      )
    }

    val url = previewUrl(result)
      ?: error("Tinymist did not return a preview server address: $result")
    LOG.info("Tinymist preview ready for " + key.filepath + " at " + url)
    return PreviewSession(taskId, url)
  }

  private suspend fun killPreview(project: Project, taskId: String) {
    runningServer(project)?.sendRequest {
      it.workspaceService.executeCommand(
        ExecuteCommandParams(
          KILL_PREVIEW_COMMAND,
          listOf(taskId),
        ),
      )
    }
  }

  private suspend fun waitForServer(project: Project): LspClient? =
    TypstLanguageServerManager.waitForServer(
      LspClientManager.getInstance(project),
      TypstLspServerSupportProvider::class.java,
    )

  private fun runningServer(project: Project): LspClient? =
    LspClientManager.getInstance(project)
      .getClients(TypstLspServerSupportProvider::class.java)
      .firstOrNull { it.state == LspServerState.Running }

  private fun previewArguments(filepath: String, taskId: String): List<String> = listOf(
    "--task-id",
    taskId,
    "--data-plane-host",
    DATA_PLANE_HOST,
    filepath,
  )

  private fun previewUrl(result: Any?): String? {
    val port = field(result, STATIC_SERVER_PORT)?.toIntValue()
    if (port != null && port in 1..MAX_PORT) {
      return "http://$LOOPBACK_HOST:$port"
    }

    return field(result, STATIC_SERVER_ADDRESS)
      ?.toStringValue()
      ?.takeIf(String::isNotBlank)
      ?.let(::normalizePreviewServerAddress)
  }

  private fun field(result: Any?, name: String): Any? = when (result) {
    is Map<*, *> -> result[name]
    is JsonObject -> result.get(name)
    else -> null
  }

  private fun Any.toIntValue(): Int? = when (this) {
    is Number -> toInt()
    is JsonPrimitive -> runCatching { asInt }.getOrNull()
    is String -> toIntOrNull()
    else -> null
  }

  private fun Any.toStringValue(): String? = when (this) {
    is String -> this
    is JsonPrimitive -> runCatching { asString }.getOrNull()
    else -> null
  }

  companion object {
    private const val START_PREVIEW_COMMAND = "tinymist.doStartPreview"
    private const val KILL_PREVIEW_COMMAND = "tinymist.doKillPreview"
    private const val STATIC_SERVER_PORT = "staticServerPort"
    private const val STATIC_SERVER_ADDRESS = "staticServerAddr"
    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val DATA_PLANE_HOST = "$LOOPBACK_HOST:0"
    private const val MAX_PORT = 65_535

    private val instance = TinymistPreviewServerManager()

    fun getInstance(): PreviewServerManager = instance
  }
}

internal fun normalizePreviewServerAddress(address: String): String =
  address.trim().let { normalized ->
    if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
      normalized
    } else {
      "http://$normalized"
    }
  }
