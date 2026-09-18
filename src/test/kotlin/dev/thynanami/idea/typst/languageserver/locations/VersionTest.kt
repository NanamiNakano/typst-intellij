package dev.thynanami.idea.typst.languageserver.locations

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class VersionTest {
  @Test
  fun `test version creation and properties`() {
    val version = Version(1, 2, 3)
    assertEquals(1, version.major)
    assertEquals(2, version.minor)
    assertEquals(3, version.patch)
  }

  @Test
  fun `test toPathString`() {
    val version = Version(1, 2, 3)
    assertEquals("v1.2.3", version.toPathString())
  }

  @Test
  fun `test toConsoleString`() {
    val version = Version(1, 2, 3)
    assertEquals("tinymist v1.2.3", version.toConsoleString())
  }


  @Test
  fun `test parseVersion with valid input`() {
    val validInputs = listOf(
      "tinymist 1.2.3",
      "Tinymist 1.2.3",
      "TINYMIST 1.2.3",
      "tinymist 10.20.30"
    )

    validInputs.forEach { input ->
      val version = Version.parseVersion(input)
      assertNotNull(version, "Failed to parse: $input")
      when (input) {
        "tinymist 1.2.3" -> {
          assertEquals(1, version?.major)
          assertEquals(2, version?.minor)
          assertEquals(3, version?.patch)
        }

        "tinymist 10.20.30" -> {
          assertEquals(10, version?.major)
          assertEquals(20, version?.minor)
          assertEquals(30, version?.patch)
        }
      }
    }
  }

  @Test
  fun `test parseVersion with invalid input`() {
    val invalidInputs = listOf(
      "tinymist",
      "tinymist abc.def.ghi",
      "tinymist 1.2",
      "not tinysssmist 1.2.3",
      "tinymist 1.2.extra",
    )

    invalidInputs.forEach { input ->
      val version = Version.parseVersion(input)
      assertNull(version, "Should not parse: $input")
    }
  }

  @Test
  fun `test data class equality`() {
    val version1 = Version(1, 2, 3)
    val version2 = Version(1, 2, 3)
    val version3 = Version(1, 2, 4)

    assertEquals(version1, version2)
    assertNotEquals(version1, version3)
  }
}
