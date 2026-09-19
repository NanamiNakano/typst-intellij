package dev.thynanami.idea.typst.languageserver

import com.google.gson.JsonObject
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import dev.thynanami.idea.typst.config.TypstSettings
import dev.thynanami.idea.typst.isTypstFile
import java.nio.file.Path

class TinymistLanguageServerDescriptor(private val languageServerPath: Path, project: Project) :
    LspClientDescriptor(
        project,
        "Tinymist",
        // filtering for a file system path allows us to get around some Jupyter strangeness, where
        // a file called Remote Server is said to be one of the base directories, causing the
        // language server to crash
        *project.getBaseDirectories().filter { it.hasFileSystemPath() }.toTypedArray()
    ) {

    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient =
        TypstLspClient(project, handler)

    override fun createCommandLine(): GeneralCommandLine =
        GeneralCommandLine(languageServerPath.toString())

    override fun isSupportedFile(file: VirtualFile): Boolean = file.isTypstFile()

    override val lspCustomization: LspCustomization = object : LspCustomization() {
        override val diagnosticsCustomizer: LspDiagnosticsSupport = TypstDiagnosticsSupport()

        override val formattingCustomizer: LspFormattingSupport = object : LspFormattingSupport() {
            override fun shouldFormatThisFileExclusivelyByServer(
                file: VirtualFile,
                ideCanFormatThisFileItself: Boolean,
                serverExplicitlyWantsToFormatThisFile: Boolean,
            ): Boolean = true
        }
    }

    override fun createInitializationOptions(): JsonObject = JsonObject().apply {
        addProperty("formatterMode", TypstSettings.getInstance().formatter.toString())
        addProperty("customizedShowDocument", true)
    }
}

private fun VirtualFile.hasFileSystemPath(): Boolean =
    path.startsWith("/") || path.contains("\\")
