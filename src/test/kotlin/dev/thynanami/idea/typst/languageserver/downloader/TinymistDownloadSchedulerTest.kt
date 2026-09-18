package dev.thynanami.idea.typst.languageserver.downloader

import dev.thynanami.idea.typst.languageserver.TypstLanguageServerManager
import dev.thynanami.idea.typst.languageserver.locations.TinymistLocationResolver
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.assertIs

class TinymistDownloadSchedulerTest {
  @AfterEach
  fun unMock() {
    unmockkAll()
    TinymistDownloadScheduler.resetDownloadingStatus()
  }

  private fun mockkImmediatePooledThread() {
    val callback = slot<Runnable>()
    mockkStatic(ApplicationManager::class)
    every { ApplicationManager.getApplication() } returns mockk {
      every { executeOnPooledThread(capture(callback)) } answers {
        callback.captured.run()
        CompletableFuture.completedFuture(0)
      }
    }
  }

  private fun mockkNeverExecutePooledThread() {
    mockkStatic(ApplicationManager::class)
    every { ApplicationManager.getApplication() } returns mockk {
      every { executeOnPooledThread(any()) } answers {
        CompletableFuture.completedFuture(0)
      }
    }
  }

  private data class Mocks(
    // dependencies
    val resolver: TinymistLocationResolver,
    val downloader: TinymistDownloader,
    val fileSystem: Filesystem,
    val lspManager: TypstLanguageServerManager,
    // objects
    val project: Project,
    val lsPath: Path,
    val lsPathParent: Path,
    val lsUrl: URI
  )

  private fun setupMocks(languageServerExists: Boolean): Mocks {
    val project = mockk<Project>()
    val lsPathParent = mockk<Path>()
    val lsPath = mockk<Path> {
      every { parent } returns lsPathParent
    }
    val fileSystem = mockk<Filesystem> {
      every { exists(lsPath) } returns languageServerExists
      every { createDirectories(lsPathParent) } returns lsPathParent
      every { setExecutable(lsPath) } returns lsPath
    }
    val lsUrl = mockk<URI>()
    val resolver = mockk<TinymistLocationResolver> {
      every { binaryPath() } returns lsPath
      every { downloadUrl() } returns lsUrl
    }
    val downloader = mockk<TinymistDownloader>(relaxed = true)
    val lspManager = mockk<TypstLanguageServerManager>(relaxed = true)
    return Mocks(resolver, downloader, fileSystem, lspManager, project, lsPath, lsPathParent, lsUrl)
  }

  @Test
  fun givenAlreadyDownloaded_ShouldCallbackImmediately() {
    // Arrange
    val (resolver, downloader, fileSystem, lspManager, project, lsPath, lsPathParent, lsUrl) = setupMocks(true)

    val scheduler = TinymistDownloadScheduler(resolver, downloader, fileSystem, lspManager)

    // Act
    val status = scheduler.obtainLanguageServerBinary(project)

    // Assert
    assertEquals(lsPath, (status as DownloadStatus.Downloaded).path)

    verify(exactly = 0) {
      fileSystem.createDirectories(lsPathParent)
      fileSystem.setExecutable(lsPath)
    }
    coVerify(exactly = 0) {
      lspManager.initialStart(project)
      downloader.download(project, lsUrl, lsPath)
    }
  }

  @Test
  fun givenNotYetDownloaded_ShouldCallbackAfterDownloading() {
    mockkImmediatePooledThread()
    val (resolver, downloader, fileSystem, lspManager, project, lsPath, lsPathParent, lsUrl) = setupMocks(false)

    val scheduler = TinymistDownloadScheduler(resolver, downloader, fileSystem, lspManager)

    // Act
    val status = scheduler.obtainLanguageServerBinary(project)

    // Assert
    assertIs<DownloadStatus.Scheduled>(status)
    verify(exactly = 1) {
      fileSystem.createDirectories(lsPathParent)
      fileSystem.setExecutable(lsPath)

    }
    coVerify(exactly = 1) {
      lspManager.initialStart(project)
      downloader.download(project, lsUrl, lsPath)
    }
  }

  @Test
  fun givenMidDownload_ShouldDoNothing() {
    mockkNeverExecutePooledThread()
    val (resolver, downloader, fileSystem, lspManager, project, lsPath, lsPathParent, lsUrl) = setupMocks(false)

    val scheduler = TinymistDownloadScheduler(resolver, downloader, fileSystem, lspManager)

    // Act
    // Start Download (callbacks never called because of mock to pooled thread executor)
    scheduler.obtainLanguageServerBinary(project)
    // Change mocks to ensure callbacks would be called if download would proceed or complete
    mockkImmediatePooledThread()
    val status = scheduler.obtainLanguageServerBinary(project)

    // Assert
    // Neither callback called, nor download initiated
    assertIs<DownloadStatus.Downloading>(status)
    verify(exactly = 0) {
      fileSystem.createDirectories(lsPathParent)
      fileSystem.setExecutable(lsPath)
    }
    coVerify(exactly = 0) {
      lspManager.initialStart(project)
      downloader.download(project, lsUrl, lsPath)
    }
  }
}
