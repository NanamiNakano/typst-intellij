package dev.thynanami.idea.typst.typm

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TypmSyncRunner {
    suspend fun sync(project: Project, layout: TypmProjectLayout, executablePath: String) {
        withBackgroundProgress(project, "Sync typm packages") {
            withContext(Dispatchers.IO) {
                coroutineToIndicator { indicator ->
                    run(createCommandLine(layout, executablePath), indicator)
                }
            }
        }
    }

    private fun createCommandLine(layout: TypmProjectLayout, executablePath: String): GeneralCommandLine {
        check(layout.exists()) { "The project's typm.toml no longer exists" }
        return GeneralCommandLine(
            TypmExecutable().resolve(executablePath),
            "--manifest-path", layout.manifest.toString(), "sync",
        ).withWorkDirectory(layout.root.toString()).withCharset(Charsets.UTF_8)
    }

    private fun run(commandLine: GeneralCommandLine, indicator: ProgressIndicator) {
        indicator.checkCanceled()
        val handler = CapturingProcessHandler(commandLine)
        val output = try {
            handler.runProcessWithProgressIndicator(indicator)
        } finally {
            if (!handler.isProcessTerminated) handler.destroyProcess()
        }
        if (output.isCancelled) throw CancellationException("typm sync was cancelled")
        indicator.checkCanceled()
        if (output.exitCode != 0) {
            val details = listOf(output.stderr, output.stdout).filter { it.isNotBlank() }.joinToString("\n").trim()
            logger<TypmSyncRunner>().warn("typm sync exited with code ${output.exitCode}: $details")
            error("typm sync failed (exit code ${output.exitCode})" + if (details.isEmpty()) "" else ":\n${details.take(4000)}")
        }
    }
}
