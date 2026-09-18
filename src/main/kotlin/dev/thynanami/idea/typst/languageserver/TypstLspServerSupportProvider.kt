package dev.thynanami.idea.typst.languageserver

import dev.thynanami.idea.typst.TypstIcons
import dev.thynanami.idea.typst.configuration.SettingsConfigurable
import dev.thynanami.idea.typst.languageserver.downloader.Filesystem
import dev.thynanami.idea.typst.languageserver.downloader.TinymistDownloadScheduler
import dev.thynanami.idea.typst.languageserver.downloader.TinymistDownloader
import dev.thynanami.idea.typst.languageserver.locations.TinymistLocationResolver
import dev.thynanami.idea.typst.languageserver.locations.isSupportedTypstFileType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsActions
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem


class TypstLspServerSupportProvider : LspIntegrationProvider {
  private val downloadScheduler by lazy {
    TinymistDownloadScheduler(
      TinymistLocationResolver(),
      TinymistDownloader(),
      Filesystem(),
      TypstLanguageServerManager()
    )
  }

  override fun fileOpened(
    project: Project,
    file: VirtualFile,
    clientStarter: LspIntegrationProvider.LspClientStarter
  ) {
    if (!file.isSupportedTypstFileType()) {
      return
    }

    TypstManager(downloadScheduler, project, clientStarter).startIfRequired()
  }

  override fun createWidgetItem(
    lspClient: LspClient,
    currentFile: VirtualFile?
  ): LspClientWidgetItem? {
    return object : LspClientWidgetItem(
      lspClient,
      currentFile,
      TypstIcons.WIDGET_ICON,
      SettingsConfigurable::class.java
    ) {
      override val widgetActionText: @NlsActions.ActionText String
        get() = "Typst (Tinymist)"
    }
  }
}
