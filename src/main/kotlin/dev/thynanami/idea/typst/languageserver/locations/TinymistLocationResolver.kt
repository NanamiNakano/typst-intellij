package dev.thynanami.idea.typst.languageserver.locations

import dev.thynanami.idea.typst.configuration.BinarySource
import dev.thynanami.idea.typst.configuration.DefaultPathValidator
import dev.thynanami.idea.typst.configuration.PathValidation
import dev.thynanami.idea.typst.configuration.SettingsState
import dev.thynanami.idea.typst.notifier.Notifier
import net.harawata.appdirs.AppDirsFactory
import java.net.URI
import java.nio.file.Path

class TinymistLocationResolver : LocationResolver {
  private val jnaNoClassPathKey = "jna.noclasspath"
  private var jnaNoClassPath: String? = null
  private val pathValidator = DefaultPathValidator()

  private val binary =
    TinymistBinary(
      osName = OsName.fromString(System.getProperty("os.name")),
      osArchitecture = OsArchitecture.fromString(System.getProperty("os.arch")),
    )

  private fun pushJnaNoClassPathFalse() {
    jnaNoClassPath = System.getProperty(jnaNoClassPathKey)
    System.setProperty(jnaNoClassPathKey, "false")
  }

  private fun popJnaNoClassPath() {
    jnaNoClassPath?.let { System.setProperty(jnaNoClassPathKey, it) }
      ?: run { System.clearProperty(jnaNoClassPathKey) }
  }

  override fun downloadUrl(): URI = binary.downloadUrl

  override fun binaryPath(): Path {
    val settings = SettingsState.getInstance()
    if (settings.state.binarySource == BinarySource.USE_CUSTOM_BINARY) {
      when (val result = pathValidator.validateBinaryFile(settings.state.customBinaryPath)) {
        is PathValidation.Failed -> {
          Notifier.warn(
            "Your specified Tinymist binary (${settings.state.customBinaryPath}) is invalid: ${result.message}.\n\n Falling back to automatically downloaded Tinymist."
          )
        }

        PathValidation.Success -> return Path.of(settings.state.customBinaryPath)
      }
    }

    pushJnaNoClassPathFalse()

    val appDirs = AppDirsFactory.getInstance()
    val path =
      Path.of(appDirs.getUserDataDir("TypstSupport", null, "dev.thynanami"))
        .resolve("language-server")
        .resolve(TinymistBinary.DOWNLOAD_VERSION)
        .resolve(binary.binaryFilename)

    popJnaNoClassPath()
    return path
  }
}
