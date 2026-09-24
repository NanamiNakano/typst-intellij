package dev.thynanami.idea.typst.lsp.status

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.widget.StatusBarEditorBasedWidgetFactory
import dev.thynanami.idea.typst.isTypstFile
import kotlinx.coroutines.CoroutineScope
import org.jetbrains.annotations.PropertyKey

internal enum class TypstStatusIndicator(
    val id: String,
    @PropertyKey(resourceBundle = STATUS_BUNDLE) val displayNameKey: String,
    val enabledByDefault: Boolean = false,
) {
    WORDS("TypstWordCount", "widget.words", true),
    CHARACTERS("TypstCharacterCount", "widget.characters"),
    PAGES("TypstPageCount", "widget.pages"),
    FILE("TypstCompiledFile", "widget.file"),
    COMPILATION("TypstCompilationStatus", "widget.compilation"),
}

abstract class TypstStatusWidgetFactory internal constructor(private val indicator: TypstStatusIndicator) :
    StatusBarEditorBasedWidgetFactory() {

    override fun getId(): String = indicator.id

    override fun getDisplayName(): String = statusMessage(indicator.displayNameKey)

    override fun isEnabledByDefault(): Boolean = indicator.enabledByDefault

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = getFileEditor(statusBar)?.file?.isTypstFile() == true

    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget =
        createTypstStatusWidget(project, indicator)
}

class TypstWordCountWidgetFactory : TypstStatusWidgetFactory(TypstStatusIndicator.WORDS)

class TypstCharacterCountWidgetFactory : TypstStatusWidgetFactory(TypstStatusIndicator.CHARACTERS)

class TypstPageCountWidgetFactory : TypstStatusWidgetFactory(TypstStatusIndicator.PAGES)

class TypstCompiledFileWidgetFactory : TypstStatusWidgetFactory(TypstStatusIndicator.FILE)

class TypstCompilationStatusWidgetFactory : TypstStatusWidgetFactory(TypstStatusIndicator.COMPILATION)
