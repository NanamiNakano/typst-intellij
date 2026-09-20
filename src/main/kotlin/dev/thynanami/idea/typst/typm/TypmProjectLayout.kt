package dev.thynanami.idea.typst.typm

import com.intellij.execution.configurations.GeneralCommandLine
import java.nio.file.Files
import java.nio.file.Path

class TypmProjectLayout(basePath: String?) {
    val root: Path? = basePath?.let { Path.of(it).toAbsolutePath().normalize() }
    val manifest: Path? = root?.resolve("typm.toml")
    val packages: Path? = root?.resolve(".typm/packages")

    fun exists(): Boolean = manifest?.let(Files::isRegularFile) == true

    fun applyEnvironment(commandLine: GeneralCommandLine): GeneralCommandLine {
        if (exists()) {
            commandLine.withEnvironment("TYPST_PACKAGE_PATH", packages.toString())
        }
        return commandLine
    }
}
