package dev.thynanami.idea.typst.configuration

import dev.thynanami.idea.typst.languageserver.locations.Version

sealed interface ExecutionValidation {
  data class Success(val version: Version) : ExecutionValidation
  data class Failed(val message: String) : ExecutionValidation
}

