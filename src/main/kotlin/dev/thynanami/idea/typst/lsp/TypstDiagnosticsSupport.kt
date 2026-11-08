package dev.thynanami.idea.typst.lsp

import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import org.eclipse.lsp4j.Diagnostic

class TypstDiagnosticsSupport : LspDiagnosticsSupport() {
    override fun getMessage(diagnostic: Diagnostic): String = diagnostic.text.firstLine

    override fun getTooltip(diagnostic: Diagnostic): String = diagnostic.text.asHtml
}

private val Diagnostic.text: String
    get() = if (message.isLeft) message.left else message.right.value

private val String.firstLine: String
    get() = lineSequence().first()

private val String.asHtml: String
    get() = StringUtil.escapeXmlEntities(this).replace("\n", "<br>")
