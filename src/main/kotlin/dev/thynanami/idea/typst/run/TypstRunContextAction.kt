package dev.thynanami.idea.typst.run

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.actions.RunContextAction
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor

class TypstRunContextAction(private val mode: TypstRunMode) :
    RunContextAction(DefaultRunExecutor.getRunExecutorInstance()) {

    override fun findExisting(context: ConfigurationContext) = when (mode) {
        TypstRunMode.COMPILE -> RunConfigurationProducer.getInstance(TypstCompileConfigurationProducer::class.java)
        TypstRunMode.WATCH -> RunConfigurationProducer.getInstance(TypstWatchConfigurationProducer::class.java)
    }.findExistingConfiguration(context)

    override fun isEnabledFor(configuration: RunConfiguration) =
        configuration is TypstRunConfiguration && configuration.mode == mode && super.isEnabledFor(configuration)
}
