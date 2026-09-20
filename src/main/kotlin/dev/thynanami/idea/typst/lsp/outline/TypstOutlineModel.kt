package dev.thynanami.idea.typst.lsp.outline

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import dev.thynanami.idea.typst.lsp.DocumentOutline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@Service(Service.Level.PROJECT)
class TypstOutlineModel(private val scope: CoroutineScope) {
    private val outline = MutableStateFlow(DocumentOutline())

    fun update(value: DocumentOutline) {
        outline.value = value
    }

    fun observe(onUpdate: (DocumentOutline) -> Unit): Job = scope.launch(Dispatchers.EDT) {
        outline.collect { onUpdate(it) }
    }
}
