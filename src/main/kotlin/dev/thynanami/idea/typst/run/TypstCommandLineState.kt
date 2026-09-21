package dev.thynanami.idea.typst.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import dev.thynanami.idea.typst.typm.TypmExecutable
import dev.thynanami.idea.typst.typm.TypmExecutableException
import dev.thynanami.idea.typst.typm.TypmProjectLayout
import dev.thynanami.idea.typst.typm.TypmProjectSettings
import java.nio.file.Files

class TypstCommandLineState(
    environment: ExecutionEnvironment,
    private val inputFile: VirtualFile,
    private val compiler: TypstCompiler,
    private val mode: TypstRunMode,
) : CommandLineState(environment) {
    override fun startProcess(): ProcessHandler {
        if (!inputFile.isValid || !inputFile.isInLocalFileSystem || !Files.isRegularFile(inputFile.toNioPath())) {
            throw ExecutionException("The typst input file does not exist or is not a local file: ${inputFile.path}")
        }

        val project = environment.project
        val layout = TypmProjectLayout(project.basePath)
        val commandLine = if (compiler == TypstCompiler.TYPM) {
            if (!layout.exists()) {
                throw ExecutionException("The selected typm compiler requires typm.toml in the project root.")
            }
            val executable = try {
                TypmExecutable().resolve(project.service<TypmProjectSettings>().executablePath)
            } catch (exception: TypmExecutableException) {
                throw ExecutionException(exception.message, exception)
            }
            GeneralCommandLine(
                executable,
                "--manifest-path", layout.manifest.toString(),
                mode.command, "--format", "pdf", inputFile.path,
            )
        } else {
            val executable = PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("typst")?.absolutePath
                ?: throw ExecutionException("Cannot find typst on PATH. Install typst to compile PDF files.")
            GeneralCommandLine(executable, mode.command, "--format", "pdf", inputFile.path)
        }
        commandLine.withWorkDirectory(layout.root?.toString() ?: inputFile.parent.path)
            .withCharset(Charsets.UTF_8)

        return KillableColoredProcessHandler(commandLine).also {
            ProcessTerminatedListener.attach(it, project)
        }
    }
}
