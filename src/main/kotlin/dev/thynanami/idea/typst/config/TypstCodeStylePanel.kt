package dev.thynanami.idea.typst.config

import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleConstraints
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.update.UiNotifyConnector
import dev.thynanami.idea.typst.TypstFileType
import dev.thynanami.idea.typst.TypstLanguage
import dev.thynanami.idea.typst.lsp.TinymistFormattingOptions
import java.awt.BorderLayout
import java.nio.file.Path
import javax.swing.JPanel

internal class TypstCodeStylePanel(
    currentSettings: CodeStyleSettings,
    settings: CodeStyleSettings,
    pluginPath: Path,
) : CodeStyleAbstractPanel(TypstLanguage, currentSettings, settings) {
    private lateinit var indentField: JBTextField
    private lateinit var printWidthField: JBTextField
    private lateinit var proseWrap: JBCheckBox
    private var source = typstCodeStyleSample
    private var updatingControls = false
    private var updatingPreview = false
    private var disposed = false

    private val controls: DialogPanel = panel {
        row("Indent size (spaces):") {
            indentField = intTextField(CodeStyleConstraints.MIN_INDENT_SIZE..CodeStyleConstraints.MAX_INDENT_SIZE)
                .applyToComponent { text = "2" }.component
        }
        row("Print width:") {
            printWidthField = intTextField(1..CodeStyleConstraints.MAX_RIGHT_MARGIN)
                .applyToComponent { text = "120" }.component
        }
        row {
            proseWrap = checkBox("Wrap prose")
                .comment("Reflow prose to fit the print width.")
                .component
        }
    }
    private val errorLabel = JBLabel().apply {
        foreground = JBColor.RED
        isVisible = false
    }
    private val previewPanel: JPanel = JPanel(BorderLayout())
    private val content: JPanel = JPanel(BorderLayout(JBUI.scale(16), 0)).apply {
        add(controls, BorderLayout.WEST)
        add(previewPanel, BorderLayout.CENTER)
        add(errorLabel, BorderLayout.SOUTH)
    }
    private val preview = TypstCodeStylePreviewController(pluginPath, this, ::displayPreview, ::displayError)

    init {
        installPreviewPanel(previewPanel)
        controls.registerValidators(this)
        addPanelToWatch(controls)
        editor.document.addDocumentListener(TypstPreviewDocumentListener(::previewEdited), this)
        UiNotifyConnector.doWhenFirstShown(editor.component) { onSomethingChanged() }
    }

    override fun getPanel() = content

    override fun getFileType() = TypstFileType

    override fun getPreviewText() = typstCodeStyleSample

    override fun createHighlighter(scheme: EditorColorsScheme) =
        EditorHighlighterFactory.getInstance().createEditorHighlighter(scheme, "preview.typ", null)

    override fun getRightMargin() = if (::printWidthField.isInitialized) {
        printWidthField.text.trim().toIntOrNull()?.takeIf { it in 1..CodeStyleConstraints.MAX_RIGHT_MARGIN } ?: 120
    } else 120

    // The isolated LSP session formats the sample asynchronously.
    override fun doReformat(project: Project, psiFile: PsiFile) = psiFile

    override fun apply(settings: CodeStyleSettings) {
        val options = readOptions()
        settings.getLanguageIndentOptions(TypstLanguage).apply {
            INDENT_SIZE = options.indentSize
            CONTINUATION_INDENT_SIZE = options.indentSize
            USE_TAB_CHARACTER = false
        }
        settings.setRightMargin(TypstLanguage, options.printWidth)
        settings.getCustomSettings(TypstCodeStyleSettings::class.java).PROSE_WRAP = options.proseWrap
    }

    override fun isModified(settings: CodeStyleSettings): Boolean =
        indentField.text.trim().toIntOrNull() != settings.getIndentSize(TypstFileType) ||
            printWidthField.text.trim().toIntOrNull() != settings.getRightMargin(TypstLanguage) ||
            proseWrap.isSelected != settings.getCustomSettings(TypstCodeStyleSettings::class.java).PROSE_WRAP ||
            settings.getLanguageIndentOptions(TypstLanguage).USE_TAB_CHARACTER

    override fun resetImpl(settings: CodeStyleSettings) {
        preview.cancel()
        updatingControls = true
        try {
            indentField.text = settings.getIndentSize(TypstFileType).toString()
            printWidthField.text = settings.getRightMargin(TypstLanguage).toString()
            proseWrap.isSelected = settings.getCustomSettings(TypstCodeStyleSettings::class.java).PROSE_WRAP
            displayError(null)
        } finally {
            updatingControls = false
        }
        onSomethingChanged()
    }

    override fun onSomethingChanged() {
        if (disposed || updatingControls || updatingPreview) return
        val options = try {
            readOptions()
        } catch (error: ConfigurationException) {
            preview.cancel()
            displayError("<html>${error.messageHtml}</html>")
            return
        }
        displayError(null)
        applySettingsToModel()
        if (!editor.component.isShowing) return
        editor.settings.setRightMargin(options.printWidth)
        if (editor.document.textLength == 0 && source.isNotEmpty()) displayPreview(source)
        preview.request(source, options)
    }

    override fun processListOptions() = setOf("Indent size (spaces)", "Print width", "Wrap prose")

    override fun dispose() {
        disposed = true
        preview.cancel()
        super.dispose()
    }

    private fun readOptions(): TinymistFormattingOptions {
        val indent = indentField.text.trim().toIntOrNull()
        if (indent == null || indent !in CodeStyleConstraints.MIN_INDENT_SIZE..CodeStyleConstraints.MAX_INDENT_SIZE) {
            throw ConfigurationException("Indent size must be between ${CodeStyleConstraints.MIN_INDENT_SIZE} and ${CodeStyleConstraints.MAX_INDENT_SIZE}.")
        }
        val width = printWidthField.text.trim().toIntOrNull()
        if (width == null || width !in 1..CodeStyleConstraints.MAX_RIGHT_MARGIN) {
            throw ConfigurationException("Print width must be between 1 and ${CodeStyleConstraints.MAX_RIGHT_MARGIN}.")
        }
        return TinymistFormattingOptions(
            TypstSettings.getInstance().formatter,
            indent,
            width,
            proseWrap.isSelected,
        )
    }

    private fun displayPreview(text: String) {
        if (disposed) return
        updatingPreview = true
        try {
            setEditorText(text, true)
        } finally {
            updatingPreview = false
        }
    }

    private fun displayError(message: String?) {
        if (disposed) return
        errorLabel.text = message
        errorLabel.isVisible = message != null
    }

    private fun previewEdited() {
        if (updatingPreview || disposed) return
        source = editor.document.text
        preview.cancel()
        displayError(null)
    }
}

private class TypstPreviewDocumentListener(private val changed: () -> Unit) : DocumentListener {
    override fun documentChanged(event: DocumentEvent) = changed()
}
