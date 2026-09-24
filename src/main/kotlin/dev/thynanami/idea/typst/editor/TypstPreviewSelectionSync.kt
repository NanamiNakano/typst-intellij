package dev.thynanami.idea.typst.editor

import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorThreading
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.util.ui.UIUtil
import dev.thynanami.idea.typst.config.TypstSettings
import org.eclipse.lsp4j.Position
import java.awt.AWTEvent
import java.awt.Component
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

@Suppress("UnstableApiUsage") // EditorThreading provides the platform's editor access context.
internal class TypstPreviewSelectionSync(
  private val project: Project,
  parent: Disposable,
  private val scrollToSource: (String, Int, Int) -> Boolean,
) {
  private data class Selection(
    val filepath: String,
    val start: Position,
    val end: Position,
    val active: Position,
    val caretCount: Int,
  ) {
    fun sharesEndpoint(previous: Selection): Boolean = filepath == previous.filepath &&
      (start == previous.start || start == previous.end || end == previous.start || end == previous.end)
  }

  private class Input(val event: AWTEvent, val editor: Editor, val before: Selection)

  private val settings = service<TypstSettings>()
  private var input: Input? = null
  private var previousSelection: Selection? = null

  init {
    val queue = IdeEventQueue.getInstance()
    queue.addPreprocessor({ event ->
      beforeInput(event)
      false
    }, parent)
    queue.addPostprocessor({ event ->
      afterInput(event)
      false
    }, parent)
  }

  private fun beforeInput(event: AWTEvent) {
    if (project.isDisposed || !settings.scrollSync) {
      input = null
      previousSelection = null
      return
    }
    if (event is KeyEvent && !settings.scrollSyncOnKeyboard) return
    val component = when (event) {
      is MouseEvent -> when (event.id) {
        MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED, MouseEvent.MOUSE_DRAGGED ->
          UIUtil.getDeepestComponentAt(event.component, event.x, event.y)
        else -> null
      }
      is KeyEvent -> when (event.id) {
        KeyEvent.KEY_PRESSED, KeyEvent.KEY_TYPED ->
          KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: event.component
        else -> null
      }
      else -> null
    } ?: return

    input = EditorThreading.compute<Input?, RuntimeException> {
      val editor = CommonDataKeys.EDITOR.getData(DataManager.getInstance().getDataContext(component))
        ?: return@compute null
      if (editor.isDisposed || editor.project !== project) return@compute null
      val gutter = editor.gutter as? Component
      if (!SwingUtilities.isDescendingFrom(component, editor.contentComponent) &&
        (gutter == null || !SwingUtilities.isDescendingFrom(component, gutter))) return@compute null
      snapshot(editor)?.let { Input(event, editor, it) }
    }
  }

  private fun afterInput(event: AWTEvent) {
    val current = input?.takeIf { it.event === event } ?: return
    input = null
    if (project.isDisposed || !settings.scrollSync) return
    if (event is KeyEvent && !settings.scrollSyncOnKeyboard) return

    // Sample after input dispatch: caret movement can precede the final selection update.
    val (selection, character) = EditorThreading.compute<Pair<Selection, Int>?, RuntimeException> {
      val selection = snapshot(current.editor) ?: return@compute null
      val document = current.editor.document
      val offset = current.editor.caretModel.primaryCaret.offset
      // Keep UTF-16 positions for selection comparisons; Tinymist's preview resolver expects code points.
      val character = Character.codePointCount(document.charsSequence, offset - selection.active.character, offset)
      selection to character
    } ?: return
    if (selection == current.before || selection.caretCount != 1) return
    if (previousSelection?.let(selection::sharesEndpoint) == true) return

    if (scrollToSource(selection.filepath, selection.active.line, character)) {
      previousSelection = selection
    }
  }

  private fun snapshot(editor: Editor): Selection? {
    if (editor.isDisposed) return null
    val document = editor.document
    val file = FileDocumentManager.getInstance().getFile(document)
      ?.takeIf { it.isValid && it.isInLocalFileSystem } ?: return null
    val caret = editor.caretModel.primaryCaret
    return Selection(
      file.path,
      position(document, caret.selectionStart),
      position(document, caret.selectionEnd),
      position(document, caret.offset),
      editor.caretModel.caretCount,
    )
  }

  private fun position(document: Document, offset: Int): Position {
    if (document.textLength == 0) return Position(0, 0)
    val line = document.getLineNumber(offset)
    return Position(line, offset - document.getLineStartOffset(line))
  }
}
