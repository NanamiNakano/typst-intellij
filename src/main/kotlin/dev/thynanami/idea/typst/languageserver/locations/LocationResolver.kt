package dev.thynanami.idea.typst.languageserver.locations

import java.net.URI
import java.nio.file.Path

interface LocationResolver {
  fun downloadUrl(): URI
  fun binaryPath(): Path
}
