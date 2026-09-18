package dev.thynanami.idea.typst.configuration

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput

interface ProcessExecutor {
  fun executeProcess(commandLine: GeneralCommandLine): ProcessOutput
}
