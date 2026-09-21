package dev.thynanami.idea.typst.run

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.project.DumbAware
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class TypstRunLineMarkerContributor : RunLineMarkerContributor(), DumbAware {
    private val actions = arrayOf<AnAction>(
        TypstRunContextAction(TypstRunMode.COMPILE),
        TypstRunContextAction(TypstRunMode.WATCH),
    )

    override fun getInfo(element: PsiElement): Info? {
        if (element is PsiFile || element.firstChild != null || element.textRange.startOffset != 0) return null
        typstRunFile(element) ?: return null
        return Info(AllIcons.RunConfigurations.TestState.Run, actions) { "Compile or Watch" }
    }

    // Empty documents have no content leaf for a marker, but the producer can still run them.
    override fun producesAllPossibleConfigurations(file: PsiFile) = file.firstChild != null
}
