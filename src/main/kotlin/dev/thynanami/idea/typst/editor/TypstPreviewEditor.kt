package dev.thynanami.idea.typst.editor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import dev.thynanami.idea.typst.lsp.TinymistPreviewServer
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.BorderLayout
import java.awt.CardLayout
import java.beans.PropertyChangeListener
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

private val LOG = logger<TypstPreviewEditor>()
private const val LOADING_CARD = "loading"
private const val BROWSER_CARD = "browser"
private const val FAILED_CARD = "failed"
private const val STOPPED_CARD = "stopped"

private fun onEdt(action: () -> Unit) {
  val application = ApplicationManager.getApplication()
  if (application.isDispatchThread) action() else application.invokeLater(action)
}

class TypstPreviewEditor(private val project: Project, private val file: VirtualFile) :
  UserDataHolderBase(), FileEditor {
  private class Attempt(val server: TinymistPreviewServer, val browser: JBCefBrowser)

  // Browser ownership and all browser operations are confined to the EDT.
  private var attempt: Attempt? = null
  private val cards = JPanel(CardLayout())
  private val panel = JPanel(BorderLayout()).apply {
    add(cards, BorderLayout.CENTER)
  }

  @Volatile
  private var disposed = false

  init {
    cards.add(messagePanel("Loading preview..."), LOADING_CARD)
    cards.add(messagePanel("Failed to load preview", restart = true), FAILED_CARD)
    cards.add(messagePanel("Preview stopped", restart = true), STOPPED_CARD)
    onEdt { startPreview() }
  }

  private inner class LoadFailureHandler(private val current: Attempt) : CefLoadHandlerAdapter() {
    override fun onLoadError(
      browser: CefBrowser?,
      frame: CefFrame?,
      errorCode: CefLoadHandler.ErrorCode?,
      errorText: String?,
      failedUrl: String?,
    ) {
      if (frame?.isMain != true) return

      onEdt {
        if (!isCurrent(current) || !current.server.isActive) return@onEdt
        LOG.warn("Tinymist preview failed to load $failedUrl: $errorCode $errorText")
        current.server.stop()
        release(current)
        showCard(FAILED_CARD)
      }
    }
  }

  private fun startPreview() {
    if (disposed || project.isDisposed) return
    if (attempt?.server?.isActive == true) return
    attempt?.let { current ->
      current.server.stop()
      release(current)
    }
    showCard(LOADING_CARD)

    val browser = try {
      JBCefBrowser.createBuilder().setOffScreenRendering(false).build()
    } catch (error: Exception) {
      LOG.warn("Could not create preview browser for " + file.path, error)
      showCard(FAILED_CARD)
      return
    }
    val current = Attempt(TinymistPreviewServer(project, file.path), browser)
    attempt = current
    cards.add(browser.component, BROWSER_CARD)
    browser.jbCefClient.addLoadHandler(LoadFailureHandler(current), browser.cefBrowser)
    current.server.start(
      onAddress = { url ->
        onEdt {
          if (!isCurrent(current) || !current.server.isActive) return@onEdt
          showCard(BROWSER_CARD)
          browser.loadURL(url)
        }
      },
      onFailure = { error ->
        onEdt {
          if (!isCurrent(current)) return@onEdt
          LOG.warn("Could not start preview for " + file.path, error)
          release(current)
          showCard(FAILED_CARD)
        }
      },
      onDisposed = {
        onEdt {
          if (!isCurrent(current)) return@onEdt
          release(current)
          showCard(STOPPED_CARD)
        }
      },
    )
  }

  private fun isCurrent(current: Attempt): Boolean =
    !disposed && !project.isDisposed && attempt === current

  private fun release(current: Attempt) {
    if (attempt !== current) return
    attempt = null
    cards.remove(current.browser.component)
    current.browser.dispose()
    cards.revalidate()
    cards.repaint()
  }

  private fun showCard(name: String) {
    (cards.layout as CardLayout).show(cards, name)
  }

  private fun messagePanel(message: String, restart: Boolean = false) = JPanel(BorderLayout()).apply {
    add(JLabel(message, SwingConstants.CENTER), BorderLayout.CENTER)
    if (restart) {
      add(JPanel().apply {
        add(JButton("Restart").apply { addActionListener { startPreview() } })
      }, BorderLayout.SOUTH)
    }
  }

  override fun getComponent(): JComponent = panel

  override fun getPreferredFocusedComponent(): JComponent = attempt?.browser?.component ?: panel

  override fun getName(): String = "Preview"

  override fun setState(state: FileEditorState) = Unit

  override fun getState(level: FileEditorStateLevel): FileEditorState = FileEditorState.INSTANCE

  override fun isModified(): Boolean = false

  override fun isValid(): Boolean = !disposed

  override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit

  override fun dispose() {
    disposed = true
    onEdt {
      attempt?.let { current ->
        current.server.stop()
        release(current)
      }
    }
  }

  override fun getFile(): VirtualFile = file
}
