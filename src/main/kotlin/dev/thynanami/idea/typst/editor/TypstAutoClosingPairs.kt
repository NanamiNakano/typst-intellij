package dev.thynanami.idea.typst.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

private val autoClosingPairsKey = Key.create<TypstAutoClosingPairs>("typst.autoClosingPairs")

internal fun Caret.typstAutoClosingPairs(create: Boolean = false): TypstAutoClosingPairs? {
    getUserData(autoClosingPairsKey)?.let { return it }
    if (!create) return null
    return service<TypstAutoClosingPairsService>().create(this)
}

@Service(Service.Level.APP)
internal class TypstAutoClosingPairsService : Disposable {
    private val states = mutableSetOf<TypstAutoClosingPairs>()
    private var disposed = false

    fun create(caret: Caret): TypstAutoClosingPairs = synchronized(states) {
        check(!disposed)
        TypstAutoClosingPairs(caret, this).also {
            states += it
            caret.putUserData(autoClosingPairsKey, it)
            Disposer.register(caret, it)
        }
    }

    fun remove(state: TypstAutoClosingPairs) = synchronized(states) {
        states.remove(state)
    }

    override fun dispose() {
        // Ordinary text editors, including .typc files, can survive plugin unload.
        val remaining = synchronized(states) {
            disposed = true
            states.toList().also { states.clear() }
        }
        remaining.forEach { Disposer.dispose(it) }
    }
}

internal class TypstAutoClosingPairs(
    private val caret: Caret,
    private val owner: TypstAutoClosingPairsService,
) : Disposable {
    private val pairs = mutableListOf<TypstAutoClosingPair>()
    var pendingDeletion: TypstAutoClosingPair? = null

    fun add(openOffset: Int, closeOffset: Int, open: Char, close: Char) {
        val document = caret.editor.document
        pairs += TypstAutoClosingPair(
            document.createRangeMarker(openOffset, openOffset + 1),
            document.createRangeMarker(closeOffset, closeOffset + 1),
            open, close,
        )
    }

    fun takeAtCaret(): TypstAutoClosingPair? {
        val text = caret.editor.document.charsSequence
        pairs.removeAll { pair ->
            val keep = pair.open.isValid && pair.close.isValid &&
                pair.open.endOffset - pair.open.startOffset == 1 &&
                pair.close.endOffset - pair.close.startOffset == 1 &&
                text.getOrNull(pair.open.startOffset) == pair.openChar &&
                text.getOrNull(pair.close.startOffset) == pair.closeChar &&
                caret.offset in pair.open.endOffset..pair.close.startOffset
            if (!keep) pair.dispose()
            !keep
        }
        val index = pairs.indexOfLast { it.close.startOffset == caret.offset }
        return if (index < 0) null else pairs.removeAt(index)
    }

    fun restore(pair: TypstAutoClosingPair) {
        pairs += pair
    }

    override fun dispose() {
        if (caret.getUserData(autoClosingPairsKey) === this) caret.putUserData(autoClosingPairsKey, null)
        owner.remove(this)
        pairs.forEach { it.dispose() }
        pairs.clear()
        pendingDeletion?.dispose()
        pendingDeletion = null
    }
}

internal class TypstAutoClosingPair(
    val open: RangeMarker,
    val close: RangeMarker,
    val openChar: Char,
    val closeChar: Char,
) : Disposable {
    override fun dispose() {
        open.dispose()
        close.dispose()
    }
}
