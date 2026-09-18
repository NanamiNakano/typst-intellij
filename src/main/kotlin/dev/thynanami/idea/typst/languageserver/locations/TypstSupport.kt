package dev.thynanami.idea.typst.languageserver.locations

import com.intellij.openapi.vfs.VirtualFile

private val supportedTypstExtensions = setOf("typ")

fun VirtualFile.isSupportedTypstFileType(): Boolean {
  return supportedTypstExtensions.contains(extension)
}
