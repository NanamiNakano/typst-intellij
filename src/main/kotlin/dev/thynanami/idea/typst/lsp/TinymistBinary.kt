package dev.thynanami.idea.typst.lsp

import com.intellij.openapi.util.SystemInfo
import dev.thynanami.idea.typst.BuildConfig
import dev.thynanami.idea.typst.config.BinarySource
import dev.thynanami.idea.typst.config.TypstSettings
import dev.thynanami.idea.typst.Notifier
import java.io.File
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isExecutable
import kotlin.io.path.setPosixFilePermissions

object TinymistBinary {
  const val BUNDLED_VERSION: String = BuildConfig.TINYMIST_VERSION

  fun resolve(pluginPath: Path): Path = customBinary() ?: bundledBinary(pluginPath)

  fun pathProblem(path: String): String? {
    val file = File(path)
    return when {
      path.isBlank() -> "Specify the path of a Tinymist binary"
      !file.isFile -> "No file exists at this path"
      !file.canExecute() -> "This file is not executable"
      else -> null
    }
  }

  private fun customBinary(): Path? {
    val settings = TypstSettings.getInstance()
    if (settings.binarySource != BinarySource.CUSTOM) return null

    val problem = pathProblem(settings.customBinaryPath)
      ?: return Path.of(settings.customBinaryPath)

    Notifier.warn(
      "Your Tinymist binary (${settings.customBinaryPath}) is invalid: $problem.\n\n Falling back to the bundled Tinymist $BUNDLED_VERSION."
    )
    return null
  }

  private fun bundledBinary(pluginPath: Path): Path =
    pluginPath.resolve(BuildConfig.TINYMIST_DIRECTORY)
      .resolve(if (SystemInfo.isWindows) "tinymist.exe" else "tinymist")
      .apply { if (!isExecutable()) setPosixFilePermissions(getPosixFilePermissions() + OWNER_EXECUTE) }
}
