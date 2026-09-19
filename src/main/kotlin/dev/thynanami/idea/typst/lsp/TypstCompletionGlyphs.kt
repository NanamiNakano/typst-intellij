package dev.thynanami.idea.typst.lsp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.ui.IconDeferrer
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Path2D
import javax.swing.Icon
import kotlin.math.min

@Service
class TypstCompletionGlyphs(scope: CoroutineScope) {
    private val cache = GlyphRenderCache(scope, renderer = ::renderGlyph)

    fun icon(codePoint: Int): Icon = CompletionGlyphIcon(codePoint, JBUI.scale(16), cache)

    companion object {
        fun getInstance(): TypstCompletionGlyphs = service()
    }
}

internal data class GlyphKey(
    val codePoint: Int,
    val fontName: String,
    val fontStyle: Int,
    val fontSize: Float,
    val iconSize: Int,
)

/** Separate from IconDeferrer's cache, which is cleared on every PSI modification. */
internal class GlyphRenderCache(
    private val scope: CoroutineScope,
    private val capacity: Int = 512,
    private val renderer: (GlyphKey) -> Icon,
) {
    private class Entry {
        lateinit var task: Deferred<Icon>
        @Volatile var icon: Icon? = null
    }

    private val entries = LinkedHashMap<GlyphKey, Entry>(16, 0.75f, true)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    init {
        require(capacity > 0)
        scope.coroutineContext.job.invokeOnCompletion { synchronized(entries) { entries.clear() } }
    }

    fun peek(key: GlyphKey): Icon? = synchronized(entries) { entries[key]?.icon }

    suspend fun get(key: GlyphKey): Icon {
        val entry = synchronized(entries) {
            scope.ensureActive()
            entries[key] ?: run {
                if (entries.size >= capacity) {
                    val evictable = entries.entries.iterator()
                    while (evictable.hasNext()) {
                        if (evictable.next().value.task.isCompleted) {
                            evictable.remove()
                            break
                        }
                    }
                    // Do not grow an unbounded queue when native font calls are slow.
                    if (entries.size >= capacity) return EmptyIcon.create(key.iconSize)
                }
                Entry().also { fresh ->
                    fresh.task = scope.async(dispatcher, start = CoroutineStart.LAZY) {
                        ensureActive()
                        val icon = try {
                            renderer(key)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ProcessCanceledException) {
                            throw e
                        } catch (e: Exception) {
                            logger<TypstCompletionGlyphs>().debug("Cannot render completion glyph", e)
                            EmptyIcon.create(key.iconSize)
                        }
                        ensureActive()
                        fresh.icon = icon
                        icon
                    }
                    entries[key] = fresh
                }
            }
        }
        return entry.task.await()
    }
}

/** Width queries and painting must never perform font discovery or text layout. */
internal class CompletionGlyphIcon(
    private val codePoint: Int,
    private val size: Int,
    private val cache: GlyphRenderCache,
    private val defer: (Icon, GlyphKey, suspend (GlyphKey) -> Icon) -> Icon = { base, key, evaluator ->
        IconDeferrer.getInstance().deferAsync(base, key, evaluator)
    },
) : Icon {
    private var lastKey: GlyphKey? = null
    private var lastIcon: Icon? = null

    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val font = c?.font
        val key = GlyphKey(codePoint, font?.name ?: Font.SERIF, font?.style ?: Font.PLAIN, font?.size2D ?: 16f, size)
        val ready = cache.peek(key)
        if (ready != null) {
            ready.paintIcon(c, g, x, y)
            return
        }
        if (lastKey != key) {
            lastKey = key
            lastIcon = defer(EmptyIcon.create(size), key) { cache.get(it) }
        }
        lastIcon?.paintIcon(c, g, x, y)
    }
}

/** Called only by the cache's background worker, without an IDE read lock. */
internal fun renderGlyph(key: GlyphKey): Icon {
    val empty = EmptyIcon.create(key.iconSize)
    if (!Character.isValidCodePoint(key.codePoint) || key.codePoint in 0xD800..0xDFFF) return empty
    val font = sequenceOf(
        key.fontName, "STIX Two Math", "STIXGeneral", "Cambria Math", "Apple Symbols", "Noto Sans Math", Font.SERIF,
    ).distinct().mapNotNull { name ->
        val candidate = Font(name, key.fontStyle, 1).deriveFont(key.fontSize)
        // A missing named font silently becomes Dialog. Keep that implicit fallback from
        // taking precedence over the remaining installed math fonts.
        candidate.takeUnless { it.family == Font.DIALOG && !name.equals(Font.DIALOG, ignoreCase = true) }
    }
        .firstOrNull { it.canDisplay(key.codePoint) } ?: return empty

    // TextLayout receives the intact surrogate pair. LookupCellRenderer instead probes each UTF-16 unit.
    val text = String(Character.toChars(key.codePoint))
    val outline = TextLayout(text, font, FontRenderContext(null, true, true)).getOutline(null)
    val bounds = outline.bounds2D
    if (!bounds.x.isFinite() || !bounds.y.isFinite() || !bounds.width.isFinite() || !bounds.height.isFinite() ||
        bounds.width <= 0 || bounds.height <= 0
    ) return empty

    val available = (key.iconSize - 2).coerceAtLeast(1).toDouble()
    val scale = min(1.0, min(available / bounds.width, available / bounds.height))
    val transform = AffineTransform().apply {
        translate((key.iconSize - bounds.width * scale) / 2, (key.iconSize - bounds.height * scale) / 2)
        scale(scale, scale)
        translate(-bounds.x, -bounds.y)
    }
    return GlyphOutlineIcon(Path2D.Double(outline, transform), key.iconSize)
}

internal class GlyphOutlineIcon(outline: Path2D, private val size: Int) : Icon {
    private val outline = Path2D.Double(outline)

    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val graphics = g.create() as Graphics2D
        try {
            graphics.translate(x, y)
            c?.foreground?.let { graphics.color = it }
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.fill(outline)
        } finally {
            graphics.dispose()
        }
    }
}
