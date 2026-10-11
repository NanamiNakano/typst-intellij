package dev.thynanami.idea.typst.lsp.status

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.thynanami.idea.typst.lsp.TinymistCompilationState
import dev.thynanami.idea.typst.lsp.TinymistCompileStatus
import dev.thynanami.idea.typst.lsp.TinymistLanguageServerDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.DurationUnit.MILLISECONDS
import kotlin.time.toDuration

@Service(Service.Level.PROJECT)
class TypstStatusModel(private val scope: CoroutineScope) {
    private val state = MutableStateFlow(StatusState())

    fun serverStarted(descriptor: TinymistLanguageServerDescriptor) {
        state.update { StatusState(owner = descriptor, selectedPath = it.selectedPath) }
    }

    fun serverStopped(descriptor: TinymistLanguageServerDescriptor) {
        state.update { current ->
            if (current.owner === descriptor) StatusState(selectedPath = current.selectedPath) else current
        }
    }

    fun fileSelected(file: VirtualFile?) {
        state.update { current ->
            if (current.selectedPath == file?.path) current
            else current.copy(selectedPath = file?.path, status = null)
        }
    }

    fun focusChanged(descriptor: TinymistLanguageServerDescriptor, file: VirtualFile?) {
        // Tinymist reports paths rooted at the compilation root, not filesystem paths.
        val root = file?.let {
            descriptor.roots.firstOrNull { root -> VfsUtilCore.isAncestor(root, file, false) }
                ?: generateSequence(file.parent) { it.parent }.firstOrNull { it.findChild("typst.toml") != null }
                ?: file.parent
        }
        val reportedPath = if (file != null && root != null) {
            VfsUtilCore.getRelativePath(file, root, '/')?.let { "/$it" }
        } else null
        state.update { current ->
            if (current.owner !== descriptor) current
            else current.copy(
                focusedPath = file?.path,
                reportedPath = reportedPath,
                status = null,
                compilationSucceeded = false,
            )
        }
    }

    fun update(descriptor: TinymistLanguageServerDescriptor, status: TinymistCompileStatus) {
        state.update { current ->
            if (current.owner !== descriptor || current.selectedPath == null ||
                current.selectedPath != current.focusedPath || current.reportedPath != status.path
            ) return@update current

            val succeeded = status.status == TinymistCompilationState.SUCCESS
            current.copy(
                status = status.copy(
                    path = current.selectedPath,
                    pageCount = status.pageCount.takeIf { succeeded },
                    // The first success still carries the previous compilation's word count.
                    // Tinymist sends the new count in a subsequent success notification.
                    wordsCount = status.wordsCount.takeIf { succeeded && current.compilationSucceeded },
                ),
                compilationSucceeded = succeeded,
            )
        }
    }

    @OptIn(FlowPreview::class)
    fun observe(refreshRequests: Flow<Unit>, onUpdate: (TinymistCompileStatus?) -> Unit): Job =
        scope.launch(Dispatchers.EDT) {
            merge(
                state.map { it.status },
                // Like the platform's editor widgets, wait for asynchronous editor selection to settle.
                refreshRequests.debounce(300.toDuration(MILLISECONDS)).map { state.value.status },
            ).collect(onUpdate)
        }

    private data class StatusState(
        val owner: TinymistLanguageServerDescriptor? = null,
        val selectedPath: String? = null,
        val focusedPath: String? = null,
        val reportedPath: String? = null,
        val status: TinymistCompileStatus? = null,
        val compilationSucceeded: Boolean = false,
    )
}
