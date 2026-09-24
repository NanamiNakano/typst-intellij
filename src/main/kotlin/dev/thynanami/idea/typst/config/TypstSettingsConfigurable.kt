package dev.thynanami.idea.typst.config

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.Cell
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
    private val settings = service<TypstSettings>()

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

        lateinit var scrollSync: Cell<JBCheckBox>
        row {
            scrollSync = checkBox("Scroll preview to cursor").bindSelected(settings::scrollSync)
        }
        indent {
            buttonsGroup {
                row { radioButton("Mouse only", false) }
                row { radioButton("Mouse and keyboard", true) }
            }.bind(settings::scrollSyncOnKeyboard)
        }.enabledIf(scrollSync.selected)
    }

    override fun apply() {
        val previous = settings.state
        super.apply()
        if (settings.state.copy(
                scrollSync = previous.scrollSync,
                scrollSyncOnKeyboard = previous.scrollSyncOnKeyboard,
            ) == previous) return

        Notifier.info("Restarting Tinymist server...")
        ProjectManager.getInstance().openProjects.forEach {
            it.service<TinymistLanguageServer>().restart()
        }
    }
}
