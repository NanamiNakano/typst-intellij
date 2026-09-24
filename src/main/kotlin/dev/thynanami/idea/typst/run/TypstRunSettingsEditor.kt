package dev.thynanami.idea.typst.run

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNullableProperty
import dev.thynanami.idea.typst.isTypstMarkupFile

class TypstRunSettingsEditor(project: Project) : SettingsEditor<TypstRunConfiguration>() {
    private var inputPath = ""
    private var rootPath = ""
    private var compiler = TypstCompiler.TYPST
    private var mode = TypstRunMode.COMPILE

    private val panel = panel {
        row("Input file:") {
            @Suppress("UnstableApiUsage")
            textFieldWithBrowseButton(
                fileChooserDescriptor = FileChooserDescriptorFactory.singleFile()
                    .withTitle("Select Input File")
                    .withFileFilter { it.isTypstMarkupFile() },
                project = project,
            ).bindText(::inputPath).align(AlignX.FILL).resizableColumn()
        }
        row("Project root:") {
            @Suppress("UnstableApiUsage")
            textFieldWithBrowseButton(
                fileChooserDescriptor = FileChooserDescriptorFactory.singleDir()
                    .withTitle("Select Project Root"),
                project = project,
            ).bindText(::rootPath).align(AlignX.FILL).resizableColumn()
        }
        row("Mode:") {
            comboBox(TypstRunMode.entries).bindItem(::mode.toNullableProperty())
        }
        row("Compiler:") {
            comboBox(TypstCompiler.entries).bindItem(::compiler.toNullableProperty())
        }
    }

    override fun createEditor() = panel

    override fun resetEditorFrom(configuration: TypstRunConfiguration) {
        inputPath = configuration.inputPath
        rootPath = configuration.rootPath
        compiler = configuration.compiler
        mode = configuration.mode
        panel.reset()
    }

    override fun applyEditorTo(configuration: TypstRunConfiguration) {
        panel.apply()
        val generatedName = configuration.isGeneratedName
        configuration.inputPath = inputPath
        configuration.rootPath = rootPath
        configuration.compiler = compiler
        configuration.mode = mode
        if (generatedName) configuration.setGeneratedName()
    }
}
