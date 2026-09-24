package dev.thynanami.idea.typst.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.LocatableRunConfigurationOptions
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.thynanami.idea.typst.isTypstMarkupFile
import org.jdom.Element

enum class TypstCompiler {
    TYPST,
    TYPM;

    override fun toString() = name.lowercase()
}

enum class TypstRunMode(val command: String, private val label: String) {
    COMPILE("compile", "Compile"),
    WATCH("watch", "Watch");

    override fun toString() = label
}

class TypstRunConfigurationOptions : LocatableRunConfigurationOptions() {
    var inputPath by string()
    var rootPath by string()
    var compiler by enum(TypstCompiler.TYPST)
    var mode by enum(TypstRunMode.COMPILE)
}

class TypstRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<TypstRunConfigurationOptions>(project, factory, name) {

    override fun getOptions() = super.getOptions() as TypstRunConfigurationOptions

    var inputPath: String
        get() = options.inputPath.orEmpty()
        set(value) {
            options.inputPath = FileUtil.toSystemIndependentName(value).ifEmpty { null }
        }

    var rootPath: String
        get() = options.rootPath ?: project.basePath.orEmpty()
        set(value) {
            options.rootPath = FileUtil.toSystemIndependentName(value).ifBlank { null }
        }

    var compiler: TypstCompiler
        get() = options.compiler
        set(value) {
            options.compiler = value
        }

    var mode: TypstRunMode
        get() = options.mode
        set(value) {
            options.mode = value
        }

    override fun suggestedName(): String? = inputPath.takeIf { it.isNotEmpty() }
        ?.substringAfterLast('/')
        ?.let { "$mode [$it]" }

    override fun getActionName() = if (isGeneratedName) mode.toString() else name

    override fun getConfigurationEditor() = TypstRunSettingsEditor(project)

    override fun readExternal(element: Element) {
        super.readExternal(element)
        if (isGeneratedName && name.startsWith("Compile PDF [")) {
            name = name.replaceFirst("Compile PDF [", "Compile [")
        }
    }

    override fun checkConfiguration() {
        inputFile()
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): TypstCommandLineState? {
        if (executor.id != DefaultRunExecutor.EXECUTOR_ID) return null
        val file = try {
            inputFile()
        } catch (error: RuntimeConfigurationError) {
            throw ExecutionException(error)
        }
        return TypstCommandLineState(environment, file, rootPath.ifBlank { file.parent.path }, compiler, mode)
    }

    private fun inputFile(): VirtualFile {
        if (inputPath.isBlank()) throw RuntimeConfigurationError("Select a typst document to compile")
        val file = LocalFileSystem.getInstance().findFileByPath(inputPath)
            ?: throw RuntimeConfigurationError("The input file does not exist: $inputPath")
        if (!file.isValid || file.isDirectory || !file.isTypstMarkupFile()) {
            throw RuntimeConfigurationError("Select a local .typ document to compile")
        }
        return file
    }
}
