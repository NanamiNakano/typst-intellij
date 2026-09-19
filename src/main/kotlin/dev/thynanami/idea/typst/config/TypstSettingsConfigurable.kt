package dev.thynanami.idea.typst.config

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.toNullableProperty
import dev.thynanami.idea.typst.lsp.TinymistLanguageServer
import dev.thynanami.idea.typst.lsp.TinymistBinary
import dev.thynanami.idea.typst.Notifier

class TypstSettingsConfigurable : BoundConfigurable("Typst") {
    private val settings = TypstSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        buttonsGroup("Tinymist binary:") {
            row {
                radioButton("Bundled (${TinymistBinary.BUNDLED_VERSION})", BinarySource.BUNDLED)
            }
            row {
                val custom = radioButton("Custom", BinarySource.CUSTOM)

                @Suppress("UnstableApiUsage")
                textFieldWithBrowseButton(
                    fileChooserDescriptor = FileChooserDescriptorFactory.singleFile()
                        .withTitle("Select Tinymist Binary"),
                )
                    .bindText(settings::customBinaryPath)
                    .align(AlignX.FILL)
                    .resizableColumn()
                    .enabledIf(custom.selected)
                    .validationOnInput { field ->
                        TinymistBinary.pathProblem(field.text)?.let { error(it) }
                    }
                    .validationOnApply { field ->
                        if (custom.selected()) {
                            TinymistBinary.pathProblem(field.text)?.let { error(it) }
                        } else null
                    }
            }
        }.bind(settings::binarySource)

        row("Formatter:") {
            comboBox(TypstFormatter.entries).bindItem(settings::formatter.toNullableProperty())
        }

        row {
            checkBox("Semantic highlighting").bindSelected(settings::semanticHighlighting)
        }
    }

    override fun apply() {
        super.apply()

        Notifier.info("Restarting Tinymist server...")
        ProjectManager.getInstance().openProjects.forEach {
            TinymistLanguageServer.getInstance(it).restart()
        }
    }
}
