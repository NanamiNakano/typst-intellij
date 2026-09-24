package dev.thynanami.idea.typst.config

import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.util.xmlb.annotations.Property

@Service
@State(name = "TypstSettings", storages = [Storage("typst.xml")])
class TypstSettings : SerializablePersistentStateComponent<TypstSettings.State>(State()) {

    data class State(
        @Property val binarySource: BinarySource = BinarySource.BUNDLED,
        @Property val customBinaryPath: String = "",
        @Property val formatter: TypstFormatter = TypstFormatter.TYPSTYLE,
        @Property val semanticHighlighting: Boolean = true,
        @Property val scrollSync: Boolean = true,
        @Property val scrollSyncOnKeyboard: Boolean = false,
    )

    var binarySource: BinarySource
        get() = state.binarySource
        set(value) {
            updateState { it.copy(binarySource = value) }
        }

    var customBinaryPath: String
        get() = state.customBinaryPath
        set(value) {
            updateState { it.copy(customBinaryPath = value) }
        }

    var formatter: TypstFormatter
        get() = state.formatter
        set(value) {
            updateState { it.copy(formatter = value) }
        }

    var semanticHighlighting: Boolean
        get() = state.semanticHighlighting
        set(value) {
            updateState { it.copy(semanticHighlighting = value) }
        }

    var scrollSync: Boolean
        get() = state.scrollSync
        set(value) {
            updateState { it.copy(scrollSync = value) }
        }

    var scrollSyncOnKeyboard: Boolean
        get() = state.scrollSyncOnKeyboard
        set(value) {
            updateState { it.copy(scrollSyncOnKeyboard = value) }
        }

    companion object {
        fun getInstance(): TypstSettings = service()
    }
}
