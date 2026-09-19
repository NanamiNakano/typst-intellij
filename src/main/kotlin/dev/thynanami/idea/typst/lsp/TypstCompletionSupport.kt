package dev.thynanami.idea.typst.lsp

import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.platform.lsp.api.customization.LspCompletionSupport
import org.eclipse.lsp4j.CompletionItem
import java.util.Locale
import javax.swing.Icon

/** Keeps completion popup text out of the platform's synchronous supplementary-font lookup. */
class TypstCompletionSupport internal constructor(
    private val glyphIcon: (Int) -> Icon,
) : LspCompletionSupport() {
    constructor() : this({ TypstCompletionGlyphs.getInstance().icon(it) })

    override fun renderLookupElement(item: CompletionItem, presentation: LookupElementPresentation) {
        // This hook is also called after completionItem/resolve. Leave the CompletionItem itself intact:
        // the platform uses it for prefix matching, insertion, snippets, edits, and documentation.
        super.renderLookupElement(item, presentation)
        presentation.itemText = popupText(presentation.itemText)
        previewCodePoint(item)?.takeIf { it > 0x7f }?.let { codePoint ->
            presentation.setTypeText(codePointText(codePoint), glyphIcon(codePoint))
        }
    }

    override fun getTailText(item: CompletionItem): String? = popupText(super.getTailText(item))

    override fun getTypeText(item: CompletionItem): String? =
        previewCodePoint(item)?.takeIf { it > 0x7f }?.let(::codePointText)
            ?: popupText(super.getTypeText(item))

    private fun previewCodePoint(item: CompletionItem): Int? {
        // Tinymist puts the printable symbol in labelDetails.description. Older responses can
        // instead expose only detail, whose glyph and explicit Unicode value must agree.
        item.labelDetails?.description?.let { return singleCodePoint(it) }
        val detail = item.detail ?: return null
        val marker = ", unicode: `\\u{"
        val markerIndex = detail.indexOf(marker)
        if (markerIndex < 1 || !detail.endsWith("}`")) return null
        val codePoint = singleCodePoint(detail.substring(0, markerIndex)) ?: return null
        val hex = detail.substring(markerIndex + marker.length, detail.length - 2)
        if (hex.length !in 4..6 || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return codePoint.takeIf { hex.toIntOrNull(16) == it }
    }

    private fun singleCodePoint(text: String): Int? {
        if (text.isEmpty()) return null
        val codePoint = text.codePointAt(0)
        return codePoint.takeIf {
            Character.charCount(it) == text.length && it !in 0xd800..0xdfff
        }
    }

    private fun popupText(text: String?): String? {
        if (text == null || text.none(Char::isSurrogate)) return text
        return buildString {
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                if (codePoint >= 0x10000 || codePoint in 0xd800..0xdfff) append(codePointText(codePoint))
                else append(codePoint.toChar())
                index += Character.charCount(codePoint)
            }
        }
    }

    private fun codePointText(codePoint: Int): String =
        "U+" + codePoint.toString(16).uppercase(Locale.ROOT).padStart(4, '0')
}
