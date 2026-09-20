package dev.thynanami.idea.typst.typm

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

class TypmExecutable {
    fun problem(value: String): String? {
        if (value.isBlank()) return null
        val path = try {
            Path.of(value)
        } catch (_: InvalidPathException) {
            return "Specify a valid path to the typm executable"
        }
        return when {
            !path.isAbsolute -> "Specify an absolute path to the typm executable"
            !Files.isRegularFile(path) -> "No file exists at this path"
            !Files.isExecutable(path) -> "This file is not executable"
            else -> null
        }
    }

    fun resolve(value: String): String {
        if (value.isNotBlank()) {
            problem(value)?.let { throw TypmExecutableException(it) }
            return value
        }
        return PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("typm")?.absolutePath
            ?: throw TypmExecutableException("typm was not found on PATH. Install typm or configure its executable in Typst settings.")
    }
}

class TypmExecutableException(message: String) : IllegalStateException(message)
