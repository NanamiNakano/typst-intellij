package dev.thynanami.idea.typst.languageserver.locations

import com.intellij.ide.plugins.cl.PluginAwareClassLoader
import com.intellij.openapi.util.SystemInfo
import dev.thynanami.idea.typst.BuildConfig
import dev.thynanami.idea.typst.configuration.BinarySource
import dev.thynanami.idea.typst.configuration.DefaultPathValidator
import dev.thynanami.idea.typst.configuration.PathValidation
import dev.thynanami.idea.typst.configuration.SettingsState
import dev.thynanami.idea.typst.notifier.Notifier
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.isExecutable
import kotlin.io.path.setPosixFilePermissions

object TinymistBinary {
  const val BUNDLED_VERSION: String = BuildConfig.TINYMIST_VERSION

  fun resolve(): Path = localBinary() ?: bundledBinary()

  private fun localBinary(): Path? {
    val settings = SettingsState.getInstance().state
    if (settings.binarySource != BinarySource.USE_CUSTOM_BINARY) return null

    val validation = DefaultPathValidator().validateBinaryFile(settings.customBinaryPath)
    if (validation is PathValidation.Failed) {
      Notifier.warn(
        "Your Tinymist binary (${settings.customBinaryPath}) is invalid: ${validation.message}.\n\n Falling back to the bundled Tinymist $BUNDLED_VERSION."
      )
      return null
    }

    return Path.of(settings.customBinaryPath)
  }

  private fun bundledBinary(): Path =
    (javaClass.classLoader as PluginAwareClassLoader).pluginDescriptor.pluginPath
      .resolve(BuildConfig.TINYMIST_DIRECTORY)
      .resolve(if (SystemInfo.isWindows) "tinymist.exe" else "tinymist")
      .apply { if (!isExecutable()) setPosixFilePermissions(getPosixFilePermissions() + OWNER_EXECUTE) }
}
