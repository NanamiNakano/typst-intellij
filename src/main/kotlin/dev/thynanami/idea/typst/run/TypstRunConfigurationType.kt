package dev.thynanami.idea.typst.run

import com.intellij.execution.configurations.SimpleConfigurationType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import dev.thynanami.idea.typst.TypstIcons

class TypstRunConfigurationType : SimpleConfigurationType(
    "TypstCompilePdf",
    "typst",
    "Compile or watch a typst document",
    NotNullLazyValue.lazy { TypstIcons.TYPST },
), DumbAware {
    override fun createTemplateConfiguration(project: Project) = TypstRunConfiguration(project, this, "Compile")

    override fun getOptionsClass() = TypstRunConfigurationOptions::class.java

    override fun isEditableInDumbMode() = true
}
