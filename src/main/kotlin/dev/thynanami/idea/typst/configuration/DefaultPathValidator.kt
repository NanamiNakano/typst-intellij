package dev.thynanami.idea.typst.configuration

import java.io.File

class DefaultPathValidator : PathValidator {
  override fun validateBinaryFile(binaryPath: String): PathValidation {
    if (binaryPath.isEmpty()) {
      return PathValidation.Failed("Binary path is empty")
    }

    val file = File(binaryPath)
    if (!file.exists()) {
      return PathValidation.Failed("Binary file does not exist")
    }

    if (file.isDirectory()) {
      return PathValidation.Failed("Binary file is a directory")
    }

    if (!file.canExecute()) {
      return PathValidation.Failed("Binary file is not executable")
    }

    return PathValidation.Success
  }
}
