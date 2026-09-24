package dev.thynanami.idea.typst.lsp.status

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
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
        state.value = StatusState(descriptor)
    }

    fun serverStopped(descriptor: TinymistLanguageServerDescriptor) {
        state.update { current ->
            if (current.owner === descriptor) StatusState() else current
        }
    }

    fun update(descriptor: TinymistLanguageServerDescriptor, status: TinymistCompileStatus) {
        state.update { current ->
            if (current.owner === descriptor) current.copy(status = status) else current
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
        val status: TinymistCompileStatus? = null,
    )
}
