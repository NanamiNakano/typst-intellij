package dev.thynanami.idea.typst.lsp.status

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.PropertyKey

internal const val STATUS_BUNDLE = "messages.TypstStatusBarBundle"

private val bundle = DynamicBundle(TypstWordCountWidgetFactory::class.java, STATUS_BUNDLE)

@Nls
internal fun statusMessage(@PropertyKey(resourceBundle = STATUS_BUNDLE) key: String, vararg params: Any): String =
    bundle.getMessage(key, *params)
