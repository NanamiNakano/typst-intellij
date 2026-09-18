package dev.thynanami.idea.typst.languageserver.downloader

import dev.thynanami.idea.typst.notifier.Notifier
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

private val LOG = logger<TinymistDownloader>()

class TinymistDownloader {
  companion object {
    private const val DOWNLOAD_TIMEOUT_MS = 300000 // 5 minutes
    private const val CONNECTION_TIMEOUT_MS = 15000 // 10 seconds

    // File size constants
    private const val BYTES_PER_KB = 1024L
    private const val BYTES_PER_MB = BYTES_PER_KB * BYTES_PER_KB
  }

  suspend fun download(project: Project, uri: URI, path: Path) =
    withBackgroundProgress(
      project,
      title = "TypstSupport",
      cancellable = true,
    ) {
      val readBuffer = ByteArray(4096)
      var readLen: Int
      val destination = path.parent

      LOG.info("Downloading Tinymist from $uri")

      try {
        reportSequentialProgress(1) { reporter ->
          reporter.indeterminateStep("Checking Tinymist Language Server...")

          val archiveInputStream = createArchiveInputStream(uri)
            ?: throw IOException("Failed to create archive input stream for $uri")

          archiveInputStream.use { stream ->
            var entry: ArchiveEntry?
            var totalBytesDownloaded = 0L

            while (stream.nextEntry.also { entry = it } != null) {
              val currentEntry = entry!!

              // Skip directories
              if (currentEntry.isDirectory) {
                continue
              }

              // Extract to fixed path: just "tinymist" in the destination directory
              val extractedFile = destination.resolve(path.fileName).toFile()
              val tempFile = File(extractedFile.path + ".tmp")

              val entrySize = if (currentEntry.size >= 0) currentEntry.size else -1L
              val sizeText = if (entrySize >= 0) formatFileSize(entrySize) else "unknown size"

              LOG.info("Extracting `${currentEntry.name}` to `${extractedFile.path}` ($sizeText)")

              reporter.itemStep("Downloading ${currentEntry.name} ($sizeText)...") {
                try {
                  if (entrySize >= 0) {
                    // Known size - use sized progress
                    reportSequentialProgress(entrySize.toInt()) { innerReporter ->
                      FileOutputStream(tempFile).use { outputStream ->
                        var bytesRead = 0L
                        while (stream.read(readBuffer).also { readLen = it } != -1) {
                          ensureActive()
                          bytesRead += readLen
                          totalBytesDownloaded += readLen
                          innerReporter.sizedStep(readLen)
                          outputStream.write(readBuffer, 0, readLen)
                        }
                        LOG.info("Downloaded ${formatFileSize(bytesRead)} for entry ${currentEntry.name}")
                      }
                    }
                  } else {
                    // Unknown size - use indeterminate progress
                    FileOutputStream(tempFile).use { outputStream ->
                      var bytesRead = 0L
                      while (stream.read(readBuffer).also { readLen = it } != -1) {
                        ensureActive()
                        bytesRead += readLen
                        totalBytesDownloaded += readLen
                        outputStream.write(readBuffer, 0, readLen)
                      }
                      LOG.info("Downloaded ${formatFileSize(bytesRead)} for entry ${currentEntry.name}")
                    }
                  }

                  // Move temp file to final location atomically
                  Files.move(tempFile.toPath(), extractedFile.toPath())

                } catch (e: Exception) {
                  // Clean up temp file on error
                  if (tempFile.exists()) {
                    try {
                      tempFile.delete()
                    } catch (deleteEx: Exception) {
                      LOG.error("Failed to clean up temp file: ${tempFile.path}", deleteEx)
                    }
                  }
                  throw e
                }
              }
            }

            LOG.info("Tinymist downloaded and extracted successfully. Total size: ${formatFileSize(totalBytesDownloaded)}")
          }
        }
      } catch (e: Exception) {
        handleDownloadError(uri, e)
      }
    }

  private fun createArchiveInputStream(uri: URI): ArchiveInputStream<*>? {
    return try {
      val connection = uri.toURL().openConnection().apply {
        connectTimeout = CONNECTION_TIMEOUT_MS
        readTimeout = DOWNLOAD_TIMEOUT_MS
      }

      val inputStream = BufferedInputStream(connection.getInputStream())
      val uriString = uri.toString().lowercase()

      when {
        uriString.endsWith(".zip") -> {
          ZipArchiveInputStream(inputStream)
        }

        uriString.endsWith(".tar.gz") || uriString.endsWith(".tgz") -> {
          TarArchiveInputStream(GzipCompressorInputStream(inputStream))
        }

        uriString.endsWith(".tar") -> {
          TarArchiveInputStream(inputStream)
        }

        else -> {
          val errorMsg = "Tinymist archive was not in a recognized format: $uri"
          LOG.error(errorMsg)
          inputStream.close()
          null
        }
      }
    } catch (e: Exception) {
      LOG.error("Failed to create archive input stream for $uri", e)
      throw e
    }
  }

  private fun handleDownloadError(uri: URI, exception: Exception) {
    val (userMessage, logMessage) = when (exception) {
      is UnknownHostException -> {
        "No internet connection available" to "Failed to resolve host: ${exception.message}"
      }

      is ConnectException -> {
        "Unable to connect to download server" to "Connection failed: ${exception.message}"
      }

      is SocketTimeoutException, is TimeoutException -> {
        "Download timed out - please check your internet connection" to "Download timeout: ${exception.message}"
      }

      is SSLException -> {
        "Secure connection failed" to "SSL error: ${exception.message}"
      }

      is IOException -> {
        "Download failed due to network error" to "IO error during download: ${exception.message}"
      }

      else -> {
        "Failed to download Tinymist Language Server" to "Unexpected error: ${exception.message}"
      }
    }

    LOG.error("$logMessage (URI: $uri)", exception)

    try {
      Notifier.error("Tinymist download failed: $userMessage")
    } catch (notificationError: Exception) {
      LOG.error("Failed to show error notification", notificationError)
    }
  }

  private fun formatFileSize(bytes: Long): String {
    return when {
      bytes < BYTES_PER_KB -> String.format("%d B", bytes)
      bytes < BYTES_PER_MB -> String.format("%.1f KB", bytes.toDouble() / BYTES_PER_KB)
      else -> String.format("%.1f MB", bytes.toDouble() / BYTES_PER_MB)
    }
  }
}
