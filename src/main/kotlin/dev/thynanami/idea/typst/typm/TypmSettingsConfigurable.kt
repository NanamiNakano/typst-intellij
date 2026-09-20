package dev.thynanami.idea.typst.typm

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

class TypmSettingsConfigurable(private val project: Project) : BoundConfigurable("Typm") {
    override fun createPanel(): DialogPanel {
        val settings = project.service<TypmProjectSettings>()
        return panel {
            row("typm executable:") {
                @Suppress("UnstableApiUsage")
                textFieldWithBrowseButton(
                    fileChooserDescriptor = FileChooserDescriptorFactory.singleFile()
                        .withTitle("Select Typm Executable"),
                    project = project,
                )
                    .bindText(settings::executablePath)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .comment("Leave empty to find typm on PATH. This setting applies to this project.")
                    .validationOnInput { field -> TypmExecutable().problem(field.text)?.let { error(it) } }
                    .validationOnApply { field -> TypmExecutable().problem(field.text)?.let { error(it) } }
            }
        }
    }

    override fun apply() {
        val previous = project.service<TypmProjectSettings>().executablePath
        super.apply()
        if (project.service<TypmProjectSettings>().executablePath != previous) {
            project.service<TypmProjectService>().settingsChanged()
        }
    }
}
