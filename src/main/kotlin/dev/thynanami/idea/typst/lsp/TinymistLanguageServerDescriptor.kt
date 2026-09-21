package dev.thynanami.idea.typst.lsp

import com.google.gson.JsonObject
import com.intellij.application.options.CodeStyle
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.components.service
import com.intellij.openapi.project.BaseProjectDirectories.Companion.getBaseDirectories
import com.intellij.openapi.project.Project
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.customization.LspCompletionSupport
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.customization.LspSemanticTokensCustomizer
import com.intellij.platform.lsp.api.customization.LspSemanticTokensDisabled
import dev.thynanami.idea.typst.config.TypstSettings
import dev.thynanami.idea.typst.isTypstFile
import dev.thynanami.idea.typst.typm.TypmProjectLayout
import kotlinx.coroutines.CancellationException
import org.eclipse.lsp4j.InitializeResult
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

    private class Preview(val onDisposed: () -> Unit, val scroll: suspend (PreviewScrollRequest) -> Unit)

    private val previews = mutableMapOf<String, Preview>()
    private var stopped = false

    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient =
        TypstLspClient(project, handler, this)

    fun registerPreview(
        taskId: String,
        onDisposed: () -> Unit,
        scroll: suspend (PreviewScrollRequest) -> Unit,
    ): Boolean = synchronized(previews) {
        if (stopped) return false
        previews[taskId] = Preview(onDisposed, scroll)
        true
    }

    fun unregisterPreview(taskId: String) {
        removePreview(taskId)
    }

    fun previewDisposed(taskId: String) {
        removePreview(taskId)?.onDisposed?.invoke()
    }

    private fun removePreview(taskId: String): Preview? = synchronized(previews) {
        previews.remove(taskId)
    }

    fun navigateOutline(item: OutlineItem) {
        project.service<TinymistLanguageServer>().launch {
            val targets = synchronized(previews) { previews.values.toList() }
            for (preview in targets) {
                for (request in item.scrollRequests()) {
                    try {
                        preview.scroll(request)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        logger<TinymistLanguageServerDescriptor>().warn("Could not navigate Typst outline", error)
                    }
                }
            }
        }
    }

    override val lspServerListener: LspServerListener = PreviewListener()

    private inner class PreviewListener : LspServerListener {
        override fun serverInitialized(params: InitializeResult) {
            synchronizeTinymistCodeStyle(project)
        }

        override fun serverStopped(shutdownNormally: Boolean) {
            val removed = synchronized(previews) {
                stopped = true
                previews.values.toList().also {
                    previews.clear()
                }
            }
            removed.forEach { it.onDisposed() }
        }
    }

    override fun createCommandLine(): GeneralCommandLine =
        TypmProjectLayout(project.basePath).applyEnvironment(GeneralCommandLine(languageServerPath.toString()))

    override fun isSupportedFile(file: VirtualFile): Boolean = file.isTypstFile()

    override val lspCustomization: LspCustomization = TinymistLspCustomization()

    override fun createInitializationOptions(): JsonObject = tinymistFormattingOptions(
        CodeStyle.getSettings(project),
        TypstSettings.getInstance().formatter,
    ).toJson().apply {
        addProperty("customizedShowDocument", true)
    }
}

private class TinymistLspCustomization : LspCustomization() {
    override val completionCustomizer: LspCompletionSupport = TypstCompletionSupport()

    override val diagnosticsCustomizer: LspDiagnosticsSupport = TypstDiagnosticsSupport()

    override val semanticTokensCustomizer: LspSemanticTokensCustomizer =
        if (TypstSettings.getInstance().semanticHighlighting) TypstSemanticTokensSupport()
        else LspSemanticTokensDisabled

    override val formattingCustomizer: LspFormattingSupport = TinymistFormattingSupport()
}

private class TinymistFormattingSupport : LspFormattingSupport() {
    override fun shouldFormatThisFileExclusivelyByServer(
        file: VirtualFile,
        ideCanFormatThisFileItself: Boolean,
        serverExplicitlyWantsToFormatThisFile: Boolean,
    ): Boolean = true
}

private fun VirtualFile.hasFileSystemPath(): Boolean =
    path.startsWith("/") || path.contains("\\")
