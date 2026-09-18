package com.github.garetht.typstsupport.previewserver

import com.github.garetht.typstsupport.languageserver.TypstLanguageServerManager
import com.github.garetht.typstsupport.languageserver.TypstLspServerSupportProvider
import com.google.gson.internal.LinkedTreeMap
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.ExecuteCommandParams
import java.io.IOException
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger


private val LOG = logger<TinymistPreviewServerManager>()

class TinymistPreviewServerManager : PreviewServerManager {

  private data class ServerInfo(
    val dataPlanePort: Int,
    val controlPlanePort: Int,
    val staticServerAddress: String,
    val taskId: UUID,
    val startTime: Long = System.currentTimeMillis(),
  )

  private val servers = ConcurrentHashMap<String, ServerInfo>()
  private val portCounter = AtomicInteger(STARTING_PORT)

  override fun createServer(filepath: String, project: Project, callback: (staticServerAddress: String?) -> Unit) {
    val existingServer = servers[filepath]
    if (existingServer?.staticServerAddress != null) {
      // Double-check the process is still alive
      callback(existingServer.staticServerAddress)
      return
    }

    ApplicationManager.getApplication().executeOnPooledThread {
      runBlocking {
        // If we're at max servers, remove the oldest one
        if (servers.size >= MAX_SERVERS) {
          val oldestServer = servers.minByOrNull { it.value.startTime }
          oldestServer?.let {
            shutdownServer(
              it.key, project
            )
          }
        }

        // Try to start the server with different ports if needed
        for (attempt in 0 until MAX_START_RETRIES) {
          LOG.info("Starting server on attempt ${attempt + 1}")
          val dataPlanePort = findAvailablePort()
          val controlPlanePort = findAvailablePort()

          try {
            val taskId = UUID.randomUUID()
            val staticServerAddress = startServer(project, filepath, taskId, dataPlanePort, controlPlanePort)

            if (staticServerAddress != null) {
              servers[filepath] = ServerInfo(dataPlanePort, controlPlanePort, staticServerAddress, taskId)
            }
            callback(staticServerAddress)
            return@runBlocking
          } catch (e: Exception) {
            LOG.error(
              "Failed to start server for $filepath on attempt ${attempt + 1}: ${e.message}"
            )
            // Don't retry if we've hit max attempts
            if (attempt == MAX_START_RETRIES - 1) {
              throw e
            }
          }
        }
      }
    }
  }

  override fun shutdownServer(filepath: String, project: Project) {
    ApplicationManager.getApplication().executeOnPooledThread {
      runBlocking {
        val server = retrieveServer(project)
        servers[filepath]?.let { serverInfo ->
          server?.sendRequestSync {
            it.workspaceService.executeCommand(
              ExecuteCommandParams(
                "tinymist.doKillPreview",
                listOf(listOf(serverInfo.taskId.toString()))
              )
            )
          }
          servers.remove(filepath)
        }
      }
    }
  }

  private fun findAvailablePort(): Int {
    var attempts = 0
    while (attempts < MAX_PORT_RETRIES) {
      val port = portCounter.getAndIncrement()
      try {
        ServerSocket(port).use {
          return port
        }
      } catch (e: IOException) {
        // Port is in use, try the next one
        attempts++
        continue
      }
    }
    return portCounter.get() - 1
  }

  private suspend fun startServer(
    project: Project,
    filename: String,
    taskId: UUID,
    dataPlanePort: Int,
    controlPlanePort: Int
  ): String? {
    val options =
      TinymistPreviewOptions(
        dataPlaneHostPort = dataPlanePort,
        controlPlaneHostPort = controlPlanePort,
        partialRendering = true,
        taskId = taskId,
      )

    LOG.info("Starting server with command: $options")

    return retrieveServer(project)?.sendRequestSync {
      it.workspaceService.executeCommand(
        ExecuteCommandParams(
          "tinymist.doStartPreview",
          options.toCommandParamsArguments(filename)
        )
      ).handle { result, throwable ->
        LOG.info("Retrieved server: result: $result, $throwable")
        if (throwable != null) {
          null
        } else {
          (result as? LinkedTreeMap<*, *>)?.get("staticServerAddr") as? String
        }
      }
    }
  }


  private suspend fun retrieveServer(project: Project): LspClient? = TypstLanguageServerManager.waitForServer(
    LspClientManager.getInstance(project), TypstLspServerSupportProvider::class.java
  )

  companion object {
    private const val STARTING_PORT = 23627 // Start from default tinymist port
    private const val MAX_SERVERS = 5
    private const val MAX_START_RETRIES = 10
    private const val MAX_PORT_RETRIES = 2

    private val instance = TinymistPreviewServerManager()
    fun getInstance(): PreviewServerManager = instance
  }
}
