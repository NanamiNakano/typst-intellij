package dev.thynanami.idea.typst.languageserver.downloader

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.exists
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.setPosixFilePermissions

class Filesystem {
  fun exists(path: Path): Boolean = path.exists()

  fun createDirectories(path: Path): Path? = Files.createDirectories(path)

  private fun isPosix(): Boolean =
    FileSystems.getDefault().supportedFileAttributeViews().contains("posix")

  fun setExecutable(path: Path): Path {
    if (isPosix()) {
      path.setPosixFilePermissions(
        path.getPosixFilePermissions().plus(PosixFilePermission.OWNER_EXECUTE)
      )
    }
    return path
  }
}
