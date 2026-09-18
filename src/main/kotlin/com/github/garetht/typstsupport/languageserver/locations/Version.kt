package com.github.garetht.typstsupport.languageserver.locations

import java.util.regex.Pattern

data class Version(val major: Int, val minor: Int, val patch: Int) {
  fun toPathString(): String {
    return "v$major.$minor.$patch"
  }

  fun toConsoleString(): String {
    return "tinymist ${toPathString()}"
  }

  companion object {
    fun parseVersion(output: String): Version? {
      // Pattern to match "tinymist X.Y.Z" format
      val pattern =
        Pattern.compile("tinymist\\s+(\\d+)\\.(\\d+)\\.(\\d+)", Pattern.CASE_INSENSITIVE)
      val matcher = pattern.matcher(output)

      return if (matcher.find()) {
        try {
          val major = matcher.group(1).toInt()
          val minor = matcher.group(2).toInt()
          val patch = matcher.group(3).toInt()
          Version(major, minor, patch)
        } catch (e: NumberFormatException) {
          null
        }
      } else {
        null
      }
    }
  }
}
