package dev.thynanami.idea.typst.editor

import dev.thynanami.idea.typst.languageserver.locations.isSupportedTypstFileType
import dev.thynanami.idea.typst.previewserver.PreviewServerManager
import dev.thynanami.idea.typst.previewserver.TinymistPreviewServerManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.CardLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

private val LOG = logger<TypstPreviewEditor>()

class TypstPreviewEditor(
  private val project: Project,
  private val file: VirtualFile,
  private val previewServerManager: PreviewServerManager = TinymistPreviewServerManager.getInstance(),
) : UserDataHolderBase(), FileEditor {
  private val browser = JBCefBrowser.createBuilder()
    .setOffScreenRendering(false)
    .build()
  private val cards = JPanel(CardLayout())
  private val panel = JPanel(BorderLayout()).apply {
    add(cards, BorderLayout.CENTER)
  }

  @Volatile
  private var disposed = false

  init {
    cards.add(messagePanel("Loading preview..."), LOADING_CARD)
    cards.add(browser.component, BROWSER_CARD)
    cards.add(messagePanel("Failed to load preview"), FAILED_CARD)
    showCard(LOADING_CARD)

    browser.jbCefClient.addLoadHandler(createLoadHandler(), browser.cefBrowser)
    ApplicationManager.getApplication().invokeLater(::startPreview)
  }

  private fun createLoadHandler() = object : CefLoadHandler {
    override fun onLoadingStateChange(
      browser: CefBrowser?,
      isLoading: Boolean,
      canGoBack: Boolean,
      canGoForward: Boolean,
    ) = Unit

    override fun onLoadStart(
      browser: CefBrowser?,
      frame: CefFrame?,
      transitionType: CefRequest.TransitionType?,
    ) = Unit

    override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
      if (frame?.isMain == true) {
        LOG.info("Tinymist preview loaded with status $httpStatusCode: " + frame.url)
      }
    }

    override fun onLoadError(
      browser: CefBrowser?,
      frame: CefFrame?,
      errorCode: CefLoadHandler.ErrorCode?,
      errorText: String?,
      failedUrl: String?,
    ) {
      if (frame?.isMain != true || disposed) return

      LOG.warn("Tinymist preview failed to load $failedUrl: $errorCode $errorText")
      showCard(FAILED_CARD)
    }
  }

  private fun startPreview() {
    if (disposed) return
    if (!file.isSupportedTypstFileType()) {
      showCard(FAILED_CARD)
      return
    }

    LOG.info("Starting preview for " + file.path)
    previewServerManager.start(file.path, project).whenComplete { url, error ->
      ApplicationManager.getApplication().invokeLater {
        if (disposed) return@invokeLater

        if (error != null || url.isNullOrBlank()) {
          LOG.warn("Could not start preview for " + file.path, error)
          showCard(FAILED_CARD)
          return@invokeLater
        }

        showCard(BROWSER_CARD)
        LOG.info("Loading preview from $url")
        browser.loadURL(url)
      }
    }
  }

  private fun showCard(name: String) {
    if (disposed) return
    ApplicationManager.getApplication().invokeLater {
      if (!disposed) (cards.layout as CardLayout).show(cards, name)
    }
  }

  override fun getComponent(): JComponent = panel

  override fun getPreferredFocusedComponent(): JComponent = browser.component

  override fun getName(): String = "Preview"

  override fun setState(state: FileEditorState) = Unit

  override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

  override fun isModified(): Boolean = false

  override fun isValid(): Boolean = !disposed

  override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun dispose() {
    if (disposed) return

    disposed = true
    previewServerManager.stop(file.path, project)
    browser.dispose()
  }

  override fun getFile(): VirtualFile = file

  private companion object {
    const val LOADING_CARD = "loading"
    const val BROWSER_CARD = "browser"
    const val FAILED_CARD = "failed"

    fun messagePanel(message: String) = JPanel(BorderLayout()).apply {
      add(JLabel(message, SwingConstants.CENTER), BorderLayout.CENTER)
    }
  }
}
