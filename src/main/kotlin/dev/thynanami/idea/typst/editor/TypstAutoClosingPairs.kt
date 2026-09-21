package dev.thynanami.idea.typst.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key

private val autoClosingPairsKey = Key.create<TypstAutoClosingPairs>("typst.autoClosingPairs")

internal fun Caret.typstAutoClosingPairs(create: Boolean = false): TypstAutoClosingPairs? {
    getUserData(autoClosingPairsKey)?.let { return it }
    if (!create) return null
    return TypstAutoClosingPairs(this).also {
        putUserData(autoClosingPairsKey, it)
        Disposer.register(this, it)
    }
}

internal class TypstAutoClosingPairs(private val caret: Caret) : Disposable {
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
