package com.github.garetht.typstsupport.languageserver

import com.github.garetht.typstsupport.TypstIcons
import com.github.garetht.typstsupport.configuration.SettingsConfigurable
import com.github.garetht.typstsupport.languageserver.downloader.Filesystem
import com.github.garetht.typstsupport.languageserver.downloader.TinymistDownloadScheduler
import com.github.garetht.typstsupport.languageserver.downloader.TinymistDownloader
import com.github.garetht.typstsupport.languageserver.locations.TinymistLocationResolver
import com.github.garetht.typstsupport.languageserver.locations.isSupportedTypstFileType
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
