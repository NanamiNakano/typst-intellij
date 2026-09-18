package dev.thynanami.idea.typst.languageserver

import dev.thynanami.idea.typst.languageserver.downloader.DownloadStatus
import dev.thynanami.idea.typst.languageserver.downloader.TinymistDownloadScheduler
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspIntegrationProvider

class TypstManager(
  private val lsDownloader: TinymistDownloadScheduler,
  val project: Project,
  private val clientStarter: LspIntegrationProvider.LspClientStarter
) {
  fun startIfRequired() {
    val status = lsDownloader.obtainLanguageServerBinary(project)

    when (status) {
      is DownloadStatus.Downloaded -> {
        // This is where the server actually gets started – it is provided the
        // path to the server binary
        clientStarter.ensureClientStarted(TinymistLanguageServerDescriptor(status.path, project))
      }
      else -> {}
    }
  }
}
