package dev.thynanami.idea.typst.typm

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros

@Service(Service.Level.PROJECT)
@State(name = "TypmProjectSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class TypmProjectSettings : SimplePersistentStateComponent<TypmProjectSettings.SettingsState>(SettingsState()) {
    class SettingsState : BaseState() {
        var executablePath by string()
    }

    var executablePath: String
        get() = state.executablePath.orEmpty()
        set(value) {
            state.executablePath = value.ifBlank { null }
        }
}
