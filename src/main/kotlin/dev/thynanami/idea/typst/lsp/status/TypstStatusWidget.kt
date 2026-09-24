package dev.thynanami.idea.typst.lsp.status

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.ui.AnimatedIcon
import com.intellij.xml.util.XmlStringUtil
import dev.thynanami.idea.typst.isTypstFile
import dev.thynanami.idea.typst.lsp.TinymistCompilationState
import dev.thynanami.idea.typst.lsp.TinymistCompileStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.awt.Component
import javax.swing.Icon

internal fun createTypstStatusWidget(project: Project, indicator: TypstStatusIndicator): StatusBarWidget =
    if (indicator == TypstStatusIndicator.COMPILATION) TypstCompilationStatusWidget(project)
    else TypstTextStatusWidget(project, indicator)

private abstract class TypstStatusWidget(
    project: Project,
    protected val indicator: TypstStatusIndicator,
) : EditorBasedWidget(project), StatusBarWidget.Multiframe, StatusBarWidget.WidgetPresentation,
    FileEditorManagerListener {

    private var updates: Job? = null
    private val refreshRequests = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var latestStatus: TinymistCompileStatus? = null
    protected var visibleStatus: TinymistCompileStatus? = null
        private set

    override fun ID(): String = indicator.id

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun copy(): StatusBarWidget = createTypstStatusWidget(project, indicator)

    override fun install(statusBar: StatusBar) {
        super<EditorBasedWidget>.install(statusBar)
        myConnection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, this)
        updates = project.service<TypstStatusModel>().observe(refreshRequests) { status ->
            latestStatus = status
            refresh()
        }
        requestRefresh()
    }

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) = requestRefresh()

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) = requestRefresh()

    override fun selectionChanged(event: FileEditorManagerEvent) = requestRefresh()

    private fun requestRefresh() {
        refreshRequests.tryEmit(Unit)
    }

    private fun refresh() {
        if (isDisposed || project.isDisposed) return
        visibleStatus = latestStatus?.takeIf { it.path.isNotBlank() && getSelectedFile()?.isTypstFile() == true }
        statusBar?.updateWidget(ID())
    }

    override fun getTooltipText(): String? {
        val status = visibleStatus ?: return null
        val lines = buildList {
            add(statusMessage(indicator.displayNameKey))
            add(statusMessage("tooltip.main.file", status.path))
            add(statusMessage(when (status.status) {
                TinymistCompilationState.COMPILING -> "compilation.compiling"
                TinymistCompilationState.SUCCESS -> "compilation.success"
                TinymistCompilationState.ERROR -> "compilation.error"
                null -> "compilation.unknown"
            }))
            status.wordsCount?.let { count ->
                add(statusMessage("count.words", count.words))
                add(statusMessage("count.characters", count.chars))
                add(statusMessage("count.spaces", count.spaces))
                add(statusMessage("count.cjk.characters", count.cjkChars))
            }
            status.pageCount?.let { add(statusMessage("count.pages", it)) }
            add(statusMessage("tooltip.statistics.note"))
        }
        return XmlStringUtil.wrapInHtml(lines.joinToString("<br>") { StringUtil.escapeXmlEntities(it) })
    }

    override fun dispose() {
        updates?.cancel()
        updates = null
        latestStatus = null
        visibleStatus = null
        super<EditorBasedWidget>.dispose()
    }
}

private class TypstTextStatusWidget(project: Project, indicator: TypstStatusIndicator) :
    TypstStatusWidget(project, indicator), StatusBarWidget.TextPresentation {

    override fun getAlignment(): Float = Component.CENTER_ALIGNMENT

    override fun getText(): String {
        val status = visibleStatus ?: return ""
        return when (indicator) {
            TypstStatusIndicator.WORDS -> status.wordsCount?.let { statusMessage("count.words", it.words) }
            TypstStatusIndicator.CHARACTERS -> status.wordsCount?.let { statusMessage("count.characters", it.chars) }
            TypstStatusIndicator.PAGES -> status.pageCount?.let { statusMessage("count.pages", it) }
            TypstStatusIndicator.FILE -> status.path.substringAfterLast('/')
            TypstStatusIndicator.COMPILATION -> null
        }.orEmpty()
    }
}

private class TypstCompilationStatusWidget(project: Project) :
    TypstStatusWidget(project, TypstStatusIndicator.COMPILATION), StatusBarWidget.IconPresentation {

    private val progressIcon by lazy { AnimatedIcon.Default() }

    override fun getIcon(): Icon? = when (visibleStatus?.status) {
        TinymistCompilationState.COMPILING -> progressIcon
        TinymistCompilationState.SUCCESS -> AllIcons.General.InspectionsOK
        TinymistCompilationState.ERROR -> AllIcons.General.Error
        null -> null
    }
}
