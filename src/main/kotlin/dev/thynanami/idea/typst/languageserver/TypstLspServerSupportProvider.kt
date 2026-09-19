package dev.thynanami.idea.typst.languageserver

import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.lsWidget.LspClientWidgetItem
import dev.thynanami.idea.typst.TypstIcons
import dev.thynanami.idea.typst.config.TypstSettingsConfigurable
import dev.thynanami.idea.typst.isTypstFile
import java.nio.file.Path


class TypstLspServerSupportProvider : LspIntegrationProvider, PluginAware {
    private lateinit var pluginPath: Path

    override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
        pluginPath = pluginDescriptor.pluginPath
    }

    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter,
    ) {
        if (!file.isTypstFile()) {
            return
        }

        clientStarter.ensureClientStarted(
            TinymistLanguageServerDescriptor(TinymistBinary.resolve(pluginPath), project)
        )
    }

    override fun createWidgetItem(
        lspClient: LspClient,
        currentFile: VirtualFile?,
    ): LspClientWidgetItem {
        return object : LspClientWidgetItem(
            lspClient,
            currentFile,
            TypstIcons.TYPST,
            TypstSettingsConfigurable::class.java
        ) {
            override val widgetActionText = "Tinymist"
        }
    }
}
