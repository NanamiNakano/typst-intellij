package dev.thynanami.idea.typst.grazie

import com.intellij.grazie.spellcheck.GrazieSpellCheckingInspection
import com.intellij.grazie.text.ProblemFilter
import com.intellij.grazie.text.TextContent.TextDomain
import com.intellij.grazie.text.TextProblem
import com.intellij.spellchecker.inspections.SpellCheckingInspection.SpellCheckingScope

class TypstSpellingProblemFilter : ProblemFilter() {
    override fun shouldIgnore(problem: TextProblem): Boolean = false

    override fun shouldIgnoreTypo(problem: TextProblem): Boolean {
        val scope = when (problem.text.domain ?: return false) {
            TextDomain.COMMENTS, TextDomain.DOCUMENTATION -> SpellCheckingScope.Comments
            TextDomain.LITERALS -> SpellCheckingScope.Literals
            TextDomain.PLAIN_TEXT -> SpellCheckingScope.Code
        }
        return scope !in GrazieSpellCheckingInspection.buildAllowedScopes(problem.text.containingFile)
    }
}
