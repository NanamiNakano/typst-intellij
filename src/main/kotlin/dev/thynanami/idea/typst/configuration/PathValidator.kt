package dev.thynanami.idea.typst.configuration

interface PathValidator {
  fun validateBinaryFile(binaryPath: String): PathValidation

  companion object {
    fun String.isValidPath(): Boolean = this.isNotEmpty()
            && this.isNotBlank()
            && (this.startsWith("/") || this.contains("\\"))
  }
}
