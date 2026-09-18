package dev.thynanami.idea.typst.languageserver.locations

enum class OsArchitecture {
  Arm,
  X64;

  fun toArchPath(): String {
    return when (this) {
      Arm -> "aarch64"
      X64 -> "x86_64"
    }
  }

  companion object {
    fun fromString(arch: String?): OsArchitecture {
      val arch = arch.orEmpty().lowercase().trim()
      return when {
        "arch64" in arch -> Arm
        else -> X64
      }
    }
  }
}
