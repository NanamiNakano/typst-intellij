package com.github.garetht.typstsupport.languageserver.locations

import java.net.URI
import kotlin.io.path.Path

data class TinymistBinary(
  private val osName: OsName,
  private val osArchitecture: OsArchitecture
) {
  companion object {
    const val DOWNLOAD_VERSION = "v0.15.8"
  }

  val compressedFilename
    get() =
      Path(
        "tinymist-${this.osArchitecture.toArchPath()}-${this.osName.toOsPath()}.${this.osName.toExtensionPath()}"
      )

  val downloadUrl
    get() =
      URI(
        "https://github.com/Myriad-Dreamin/tinymist/releases/download/$DOWNLOAD_VERSION/$compressedFilename"
      )


  val binaryFilename = when (osName) {
    OsName.Mac, OsName.Linux -> Path("tinymist")
    OsName.Windows -> Path("tinymist.exe")
  }
}
