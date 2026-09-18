package dev.thynanami.idea.typst.configuration

interface ExecutionValidator {
  fun validateBinaryExecution(binaryPath: String): ExecutionValidation
}
