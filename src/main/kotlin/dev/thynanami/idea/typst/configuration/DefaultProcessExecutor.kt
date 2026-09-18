package dev.thynanami.idea.typst.configuration

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput

class DefaultProcessExecutor : ProcessExecutor {
  override fun executeProcess(commandLine: GeneralCommandLine): ProcessOutput {
    val processHandler = CapturingProcessHandler(commandLine)
    return processHandler.runProcess(10000)
  }
}
