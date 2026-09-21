package dev.thynanami.idea.typst.lsp

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.intellij.psi.codeStyle.CodeStyleSettingsChangeEvent
import com.intellij.psi.codeStyle.CodeStyleSettingsListener
import org.eclipse.lsp4j.DidChangeConfigurationParams

class TypstCodeStyleSettingsListener : CodeStyleSettingsListener {
    override fun codeStyleSettingsChanged(event: CodeStyleSettingsChangeEvent) {
        // Tinymist's formatter configuration applies to the whole server, not individual files.
        if (event.virtualFile != null) return

        // An IDE scheme can be shared by several open projects.
        ProjectManager.getInstance().openProjects.forEach(::synchronizeTinymistCodeStyle)
    }
}

internal fun synchronizeTinymistCodeStyle(project: Project) {
    if (project.isDisposed) return

    LspClientManager.getInstance(project)
        .getClients(TypstLspServerSupportProvider::class.java)
        .filter { it.state == LspServerState.Running }
        .forEach { client ->
            val descriptor = client.descriptor as? TinymistLanguageServerDescriptor ?: return@forEach
            client.sendNotification { server ->
                if (!project.isDisposed && client.state == LspServerState.Running) {
                    // Tinymist resets omitted settings, so send the complete configuration.
                    server.workspaceService.didChangeConfiguration(
                        DidChangeConfigurationParams(descriptor.createInitializationOptions())
                    )
                }
            }
        }
}
