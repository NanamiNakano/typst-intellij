package dev.thynanami.idea.typst.languageserver

import dev.thynanami.idea.typst.TypstIcons
import dev.thynanami.idea.typst.isTypstFile
import dev.thynanami.idea.typst.config.TypstSettingsConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsActions
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem


class TypstLspServerSupportProvider : LspIntegrationProvider {
  override fun fileOpened(
    project: Project,
    file: VirtualFile,
    clientStarter: LspIntegrationProvider.LspClientStarter
  ) {
    if (!file.isTypstFile()) {
      return
    }

    clientStarter.ensureClientStarted(
      TinymistLanguageServerDescriptor(TinymistBinary.resolve(), project)
    )
  }

  override fun createWidgetItem(
    lspClient: LspClient,
    currentFile: VirtualFile?
  ): LspClientWidgetItem {
    return object : LspClientWidgetItem(
      lspClient,
      currentFile,
      TypstIcons.WIDGET_ICON,
      TypstSettingsConfigurable::class.java
    ) {
      override val widgetActionText: @NlsActions.ActionText String
        get() = "Typst (Tinymist)"
    }
  }
}
