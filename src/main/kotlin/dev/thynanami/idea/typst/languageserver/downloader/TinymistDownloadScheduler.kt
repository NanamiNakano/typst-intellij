package dev.thynanami.idea.typst.languageserver.downloader

import dev.thynanami.idea.typst.languageserver.LanguageServerManager
import dev.thynanami.idea.typst.languageserver.locations.LocationResolver
import dev.thynanami.idea.typst.notifier.Notifier
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class TinymistDownloadScheduler(
  private val resolver: LocationResolver,
  private val downloader: TinymistDownloader,
  private val fileSystem: Filesystem,
  private val languageServerManager: LanguageServerManager
) {

  companion object {
    private val isDownloading = AtomicBoolean()

    fun resetDownloadingStatus() = isDownloading.set(false)

    private const val DOWNLOAD_CANCELLED_MSG =
      "The Typst Language Server download was cancelled.\n\n To retry, restart the IDE."
  }

  private suspend fun prepAndDownload(project: Project, url: URI, path: Path) {
    fileSystem.createDirectories(path.parent)
    downloader.download(project, url, path)
    fileSystem.setExecutable(path)
  }

  fun obtainLanguageServerBinary(project: Project): DownloadStatus {
    val path = resolver.binaryPath()
    if (isDownloading.get()) {
      return DownloadStatus.Downloading
    }

    // the path can also exist because the user has specified it
    // note that we assume that the existence of the file is sufficient
    // and do not check for correctness. when the user first specifies a
    // binary they will have to test it for correctness, but this will not
    // handle any later corruption
    if (fileSystem.exists(path)) {
      return DownloadStatus.Downloaded(path)
    } else {
      isDownloading.set(true)
      ApplicationManager.getApplication().executeOnPooledThread {
        runBlocking {
          try {
            val url = resolver.downloadUrl()
            prepAndDownload(project, url, path)
            isDownloading.set(false)
            languageServerManager.initialStart(project)
          } catch (ce: CancellationException) {
            Notifier.warn(DOWNLOAD_CANCELLED_MSG)
            throw ce
          } catch (_: Exception) {
            isDownloading.set(false)
          }
        }
      }
      return DownloadStatus.Scheduled
    }
  }
}
