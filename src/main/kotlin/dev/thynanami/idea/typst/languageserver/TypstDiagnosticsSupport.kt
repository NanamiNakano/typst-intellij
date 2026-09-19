package dev.thynanami.idea.typst.languageserver

import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.lsp.api.customization.LspDiagnosticsSupport
import org.eclipse.lsp4j.Diagnostic

class TypstDiagnosticsSupport : LspDiagnosticsSupport() {
    override fun getMessage(diagnostic: Diagnostic): String = diagnostic.message.firstLine

    override fun getTooltip(diagnostic: Diagnostic): String = diagnostic.message.asHtml

    private val String.firstLine: String
        get() = lineSequence().first()

    private val String.asHtml: String
        get() = StringUtil.escapeXmlEntities(this).replace("\n", "<br>")
}
