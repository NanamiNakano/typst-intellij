package dev.thynanami.idea.typst.editor

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import dev.thynanami.idea.typst.lsp.TinymistPreviewServer
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.awt.CardLayout
import java.beans.PropertyChangeListener
import java.net.URI
import java.net.URISyntaxException
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

private fun parseUri(url: String?): URI? = try {
  url?.let(::URI)
} catch (_: URISyntaxException) {
  null
}

private fun externalUri(url: String): URI? {
  val uri = parseUri(url) ?: return null
  return uri.takeIf {
    when {
      uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true) ->
        !uri.host.isNullOrEmpty() && uri.port in -1..65535
      uri.scheme.equals("mailto", ignoreCase = true) -> uri.isOpaque && uri.rawSchemeSpecificPart.isNotBlank()
      else -> false
    }
  }
}

class TypstPreviewEditor(private val project: Project, private val file: VirtualFile) :
  UserDataHolderBase(), FileEditor {
  private class Attempt(val server: TinymistPreviewServer, val browser: JBCefBrowser) {
    val linkQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

    @Volatile
    var previewUri: URI? = null

    fun isPreviewUrl(url: String?): Boolean {
      val expected = previewUri ?: return false
      val actual = parseUri(url) ?: return false
      return actual.scheme.equals(expected.scheme, ignoreCase = true) &&
        actual.host.equals(expected.host, ignoreCase = true) && actual.port == expected.port &&
        actual.rawUserInfo == expected.rawUserInfo && actual.rawQuery == expected.rawQuery &&
        actual.rawPath.orEmpty().ifEmpty { "/" } == expected.rawPath.orEmpty().ifEmpty { "/" }
    }
  }

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

  private class NavigationHandler(private val current: Attempt) : CefRequestHandlerAdapter() {
    override fun onBeforeBrowse(
      browser: CefBrowser?,
      frame: CefFrame?,
      request: CefRequest?,
      userGesture: Boolean,
      isRedirect: Boolean,
    ): Boolean = frame?.isMain != true || !current.isPreviewUrl(request?.url)

    override fun onOpenURLFromTab(
      browser: CefBrowser?,
      frame: CefFrame?,
      targetUrl: String?,
      userGesture: Boolean,
    ): Boolean = true
  }

  private class PopupHandler : CefLifeSpanHandlerAdapter() {
    override fun onBeforePopup(
      browser: CefBrowser?,
      frame: CefFrame?,
      targetUrl: String?,
      targetFrameName: String?,
    ): Boolean = true
  }

  private inner class PreviewLoadHandler(private val current: Attempt) : CefLoadHandlerAdapter() {
    override fun onLoadStart(browser: CefBrowser?, frame: CefFrame?, transitionType: CefRequest.TransitionType?) {
      if (frame?.isMain != true || !current.isPreviewUrl(frame.url)) return
      onEdt {
        if (isCurrent(current) && current.server.isActive) showCard(LOADING_CARD)
      }
    }

    override fun onLoadEnd(browser: CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
      if (frame?.isMain != true) return
      val url = frame.url
      if (!current.isPreviewUrl(url)) return
      onEdt {
        if (!isCurrent(current) || !current.server.isActive) return@onEdt
        // Capture links before their default action: javascript: links need not trigger a navigation callback.
        current.browser.cefBrowser.executeJavaScript(
          """
          (() => {
            const openLink = event => {
              const link = event.target instanceof Element ? event.target.closest('a') : null;
              const href = link?.getAttribute('href') ??
                link?.getAttributeNS('http://www.w3.org/1999/xlink', 'href');
              if (href == null || href.trim().startsWith('#')) return;
              event.preventDefault();
              event.stopImmediatePropagation();
              if (!event.isTrusted || event.button !== (event.type === 'click' ? 0 : 1)) return;
              try {
                const url = new URL(href, document.baseURI);
                ${current.linkQuery.inject("url.href")}
              } catch (_) {}
            };
            document.addEventListener('click', openLink, true);
            document.addEventListener('auxclick', openLink, true);
            ${current.linkQuery.inject("''")}
          })();
          """.trimIndent(),
          url,
          0,
        )
      }
    }

    override fun onLoadError(
      browser: CefBrowser?,
      frame: CefFrame?,
      errorCode: CefLoadHandler.ErrorCode?,
      errorText: String?,
      failedUrl: String?,
    ) {
      if (frame?.isMain != true || errorCode == CefLoadHandler.ErrorCode.ERR_ABORTED) return

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
    TypstPreviewSelectionSync(project, browser, current.server::scrollToSource)
    current.linkQuery.addHandler { url ->
      onEdt {
        if (!isCurrent(current) || !current.server.isActive) return@onEdt
        if (url.isEmpty()) {
          showCard(BROWSER_CARD)
        } else {
          externalUri(url)?.let { BrowserUtil.browse(it.toASCIIString(), project) }
        }
      }
      null
    }
    // Keep the platform's existing handlers; CefClient.addRequestHandler cannot replace them.
    browser.jbCefClient.addRequestHandler(NavigationHandler(current), browser.cefBrowser)
    browser.jbCefClient.addLifeSpanHandler(PopupHandler(), browser.cefBrowser)
    browser.jbCefClient.addLoadHandler(PreviewLoadHandler(current), browser.cefBrowser)
    browser.createImmediately()
    cards.add(browser.component, BROWSER_CARD)
    current.server.start(
      onAddress = { url ->
        onEdt {
          if (!isCurrent(current) || !current.server.isActive) return@onEdt
          current.previewUri = URI(url)
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

  fun restartPreview() = onEdt {
    if (disposed || project.isDisposed) return@onEdt
    attempt?.let { current ->
      current.server.stop()
      release(current)
    }
    startPreview()
  }

  private fun isCurrent(current: Attempt): Boolean =
    !disposed && !project.isDisposed && attempt === current

  private fun release(current: Attempt) {
    if (attempt !== current) return
    attempt = null
    cards.remove(current.browser.component)
    Disposer.dispose(current.browser)
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
