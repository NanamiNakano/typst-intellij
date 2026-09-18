package dev.thynanami.idea.typst.configuration

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.layout.ValidationInfoBuilder

// Result sealed class to represent validation outcomes
sealed interface PathValidation {
  data object Success : PathValidation

  data class Failed(val message: String) : PathValidation

  fun toValidationInfo(context: ValidationInfoBuilder): ValidationInfo? = when (this) {
    is Failed -> context.error(message)
    Success -> null
  }

  fun toConfigurationException(): ConfigurationException? = when (this) {
    is Failed -> ConfigurationException(message)
    Success -> null
  }

}

