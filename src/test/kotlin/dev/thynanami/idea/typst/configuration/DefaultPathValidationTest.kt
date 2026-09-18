package dev.thynanami.idea.typst.configuration

import dev.thynanami.idea.typst.configuration.PathValidator.Companion.isValidPath
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.layout.ValidationInfoBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class DefaultPathValidationTest {
  @TempDir
  lateinit var tempDir: File

  @Nested
  inner class ValidateBinaryFile {
    private val pathValidator = DefaultPathValidator()

    @Test
    fun `should fail with empty path`() {
      val result = pathValidator.validateBinaryFile("")
      assertIs<PathValidation.Failed>(result)
      assertEquals("Binary path is empty", result.message)
    }

    @Test
    fun `should fail with blank path`() {
      val result = pathValidator.validateBinaryFile("   ")
      assertIs<PathValidation.Failed>(result)
      assertEquals("Binary file does not exist", result.message)
    }

    @Test
    fun `should fail with non-existent file`() {
      val result = pathValidator.validateBinaryFile("/nonexistent/path")
      assertIs<PathValidation.Failed>(result)
      assertEquals("Binary file does not exist", result.message)
    }

    @Test
    fun `should fail with non-existent file in temp directory`() {
      val result = pathValidator.validateBinaryFile(File(tempDir, "nonexistent").absolutePath)
      assertIs<PathValidation.Failed>(result)
      assertEquals("Binary file does not exist", result.message)
    }

    @Test
    fun `should fail with directory`() {
      val dir = File(tempDir, "dir")
      dir.mkdir()

      val result = pathValidator.validateBinaryFile(dir.absolutePath)
      assertIs<PathValidation.Failed>(result)
      assertEquals("Binary file is a directory", result.message)
    }

    @Test
    fun `should fail with non-executable file`() {
      val file = File(tempDir, "test.txt")
      file.createNewFile()
      file.setExecutable(false)

      val result = pathValidator.validateBinaryFile(file.absolutePath)
      assertIs<PathValidation.Failed>(result)
      assertEquals("Binary file is not executable", result.message)
    }

    @Test
    fun `should succeed with executable file`() {
      val file = File(tempDir, "test")
      file.createNewFile()
      file.setExecutable(true)

      val result = pathValidator.validateBinaryFile(file.absolutePath)
      assertIs<PathValidation.Success>(result)
    }

    @Test
    fun `should handle file with spaces in path`() {
      val file = File(tempDir, "test file with spaces")
      file.createNewFile()
      file.setExecutable(true)

      val result = pathValidator.validateBinaryFile(file.absolutePath)
      assertIs<PathValidation.Success>(result)
    }

    @Test
    fun `should handle file with special characters in path`() {
      val file = File(tempDir, "test!@#$%^&*()_+-=[]{}|;:,.<>?")
      file.createNewFile()
      file.setExecutable(true)

      val result = pathValidator.validateBinaryFile(file.absolutePath)
      assertIs<PathValidation.Success>(result)
    }

    @Test
    fun `should handle file with unicode characters in path`() {
      val file = File(tempDir, "test-测试-テスト-테스트")
      file.createNewFile()
      file.setExecutable(true)

      val result = pathValidator.validateBinaryFile(file.absolutePath)
      assertIs<PathValidation.Success>(result)
    }

    @Test
    fun `should handle file with very long path`() {
      val longPath = "a".repeat(255)
      val file = File(tempDir, longPath)
      file.createNewFile()
      file.setExecutable(true)

      val result = pathValidator.validateBinaryFile(file.absolutePath)
      assertIs<PathValidation.Success>(result)
    }
  }

  @Nested
  inner class ToValidationInfo {
    private lateinit var validationInfoBuilder: ValidationInfoBuilder
    private lateinit var validationInfo: ValidationInfo

    @BeforeEach
    fun setup() {
      validationInfoBuilder = mockk()
      validationInfo = mockk()
    }

    @Test
    fun `should return ValidationInfo for Failed case`() {
      every { validationInfoBuilder.error("Test error") } returns validationInfo

      val result = PathValidation.Failed("Test error").toValidationInfo(validationInfoBuilder)

      assertEquals(validationInfo, result)
      verify { validationInfoBuilder.error("Test error") }
    }

    @Test
    fun `should return null for Success case`() {
      val result = PathValidation.Success.toValidationInfo(validationInfoBuilder)
      assertNull(result)
    }

    @ParameterizedTest
    @ValueSource(
      strings = [
        "test error",
        "another error",
        "error with special chars !@#$%^&*()",
        "error with unicode 测试 テスト 테스트"
      ]
    )
    fun `should handle various error messages`(errorMessage: String) {
      every { validationInfoBuilder.error(errorMessage) } returns validationInfo

      val result = PathValidation.Failed(errorMessage).toValidationInfo(validationInfoBuilder)

      assertEquals(validationInfo, result)
      verify { validationInfoBuilder.error(errorMessage) }
    }
  }

  @Nested
  inner class ToConfigurationException {
    @Test
    fun `should return ConfigurationException for Failed case`() {
      val result = PathValidation.Failed("test error").toConfigurationException()

      assertIs<ConfigurationException>(result)
      assertEquals("test error", result.localizedMessage)
    }

    @Test
    fun `should return null for Success case`() {
      val result = PathValidation.Success.toConfigurationException()
      assertNull(result)
    }

    @ParameterizedTest
    @ValueSource(
      strings = [
        "test error",
        "another error",
        "error with special chars !@#$%^&*()",
        "error with unicode 测试 テスト 테스트"
      ]
    )
    fun `should handle various error messages`(errorMessage: String) {
      val result = PathValidation.Failed(errorMessage).toConfigurationException()

      assertIs<ConfigurationException>(result)
      assertEquals(errorMessage, result.localizedMessage)
    }
  }

  @Nested
  inner class ValidatePath {
    private val pathValidator = DefaultPathValidator()

    @ParameterizedTest
    @ValueSource(
      strings = [
        // Linux/Unix absolute paths
        "/",
        "/home",
        "/home/user",
        "/home/user/documents",
        "/usr/bin/bash",
        "/var/log/system.log",
        "/opt/app/bin/executable",
        "/tmp/temp_file.txt",
        "/etc/passwd",
        "/home/user/file with spaces.txt",
        "/home/user/файл.txt", // Unicode filename
        "/home/user/.hidden",
        "/home/user/.config/app",
        "/path/to/very/deeply/nested/directory/structure/file.txt",

        // Windows absolute paths
        "C:\\",
        "C:\\Windows",
        "C:\\Users\\user",
        "C:\\Program Files\\App",
        "C:\\Program Files (x86)\\App",
        "D:\\Data\\file.txt",
        "E:\\Backups\\2024\\January\\backup.zip",
        "C:\\Users\\user\\Documents\\file with spaces.docx",
        "C:\\temp\\файл.txt", // Unicode on Windows
        "\\\\server\\share", // UNC path
        "\\\\server\\share\\folder",
        "\\\\server\\share\\folder\\file.txt",
      ]
    )
    fun `should return true for valid paths`(path: String) {
      assertTrue("Path should be valid: $path", path.isValidPath())
    }

    @ParameterizedTest
    @ValueSource(
      strings = [
        // Empty/null-like cases
        "",
        " ",
        "   ",
        "\t",
        "\n",

        // Malformed paths
        "C:",  // Drive without separator
        "C:file.txt", // Drive-relative (often problematic)

        // Paths that look like commands or have suspicious structure
        "Remote Server", // Your original example - space without separator
        "Network Drive",
        "Shared Folder",
        "rm -rf /",
      ]
    )
    fun `should return false for invalid paths`(path: String) {
      assertFalse("Path should be invalid: $path", path.isValidPath())
    }
  }
}
