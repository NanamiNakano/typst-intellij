package dev.thynanami.idea.typst.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.thynanami.idea.typst.isTypstMarkupFile
import dev.thynanami.idea.typst.typm.TypmProjectLayout

abstract class TypstRunConfigurationProducer(private val mode: TypstRunMode) :
    LazyRunConfigurationProducer<TypstRunConfiguration>(), DumbAware {
    override fun getConfigurationFactory() = runConfigurationType<TypstRunConfigurationType>()

    override fun setupConfigurationFromContext(
        configuration: TypstRunConfiguration,
        context: ConfigurationContext,
        sourceElement: Ref<PsiElement>,
    ): Boolean {
        val file = typstRunFile(context.psiLocation) ?: return false
        configuration.inputPath = file.virtualFile.path
        configuration.mode = mode
        configuration.compiler = if (TypmProjectLayout(context.project.basePath).exists()) TypstCompiler.TYPM else TypstCompiler.TYPST
        configuration.setGeneratedName()
        sourceElement.set(file)
        return true
    }

    override fun isConfigurationFromContext(
        configuration: TypstRunConfiguration,
        context: ConfigurationContext,
    ): Boolean {
        val file = typstRunFile(context.psiLocation) ?: return false
        return configuration.mode == mode && FileUtil.pathsEqual(configuration.inputPath, file.virtualFile.path)
    }
}

class TypstCompileConfigurationProducer : TypstRunConfigurationProducer(TypstRunMode.COMPILE)

class TypstWatchConfigurationProducer : TypstRunConfigurationProducer(TypstRunMode.WATCH)

internal fun typstRunFile(element: PsiElement?): PsiFile? {
    val file = element?.containingFile ?: return null
    val virtualFile = file.virtualFile ?: return null
    if (!file.isPhysical || !virtualFile.isValid || !virtualFile.isInLocalFileSystem ||
        !virtualFile.isTypstMarkupFile() || InjectedLanguageManager.getInstance(file.project).isInjectedFragment(file)
    ) return null
    return file
}
