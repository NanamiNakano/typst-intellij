package dev.thynanami.idea.typst.configuration

import dev.thynanami.idea.typst.languageserver.locations.Version
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class DefaultExecutionValidatorTest {
  private lateinit var processExecutor: ProcessExecutor
  private lateinit var pathValidator: PathValidator
  private lateinit var validator: DefaultExecutionValidator

  @BeforeEach
  fun setup() {
    processExecutor = mockk()
    pathValidator = mockk()
    validator = DefaultExecutionValidator(processExecutor, pathValidator)
  }

  @Test
  fun `should return success with valid version output`() {
    // Given
    val binaryPath = "/path/to/binary"
    val versionOutput = "tinymist 401.293.193"
    val expectedVersion = Version(401, 293, 193)

    every { pathValidator.validateBinaryFile(binaryPath) } returns PathValidation.Success
    every { processExecutor.executeProcess(any()) } returns ProcessOutput(versionOutput, "", 0, false, false)

    // When
    val result = validator.validateBinaryExecution(binaryPath)

    // Then
    assertInstanceOf(ExecutionValidation.Success::class.java, result)
    assertEquals(expectedVersion, (result as ExecutionValidation.Success).version)
  }

  @Test
  fun `should return failed when path validation fails`() {
    // Given
    val binaryPath = "/path/to/binary"
    val errorMessage = "Binary not found"

    every { pathValidator.validateBinaryFile(binaryPath) } returns PathValidation.Failed(errorMessage)

    // When
    val result = validator.validateBinaryExecution(binaryPath)

    // Then
    assertInstanceOf(ExecutionValidation.Failed::class.java, result)
    assertEquals(errorMessage, (result as ExecutionValidation.Failed).message)
  }

  @Test
  fun `should return failed when process exits with non-zero code`() {
    // Given
    val binaryPath = "/path/to/binary"
    val errorOutput = "Permission denied"

    every { pathValidator.validateBinaryFile(binaryPath) } returns PathValidation.Success
    every { processExecutor.executeProcess(any()) } returns ProcessOutput("", errorOutput, 1, false, false)

    // When
    val result = validator.validateBinaryExecution(binaryPath)

    // Then
    assertInstanceOf(ExecutionValidation.Failed::class.java, result)
    assertEquals("Permission denied - make binary executable", (result as ExecutionValidation.Failed).message)
  }

  @Test
  fun `should return failed when process output is invalid version format`() {
    // Given
    val binaryPath = "/path/to/binary"
    val invalidOutput = "invalid version format"

    every { pathValidator.validateBinaryFile(binaryPath) } returns PathValidation.Success
    every { processExecutor.executeProcess(any()) } returns ProcessOutput(invalidOutput, "", 0, false, false)

    // When
    val result = validator.validateBinaryExecution(binaryPath)

    // Then
    assertInstanceOf(ExecutionValidation.Failed::class.java, result)
    assertEquals("No version information could be found.", (result as ExecutionValidation.Failed).message)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "cannot be opened because the developer cannot be verified",
      "malware",
      "not trusted"
    ]
  )
  fun `should return macOS security error for various security messages`(errorMessage: String) {
    // Given
    val binaryPath = "/path/to/binary"

    every { pathValidator.validateBinaryFile(binaryPath) } returns PathValidation.Success
    every { processExecutor.executeProcess(any()) } returns ProcessOutput("", errorMessage, 126, false, false)

    // When
    val result = validator.validateBinaryExecution(binaryPath)

    // Then
    assertInstanceOf(ExecutionValidation.Failed::class.java, result)
    assertEquals(
      "macOS blocked execution - check Security & Privacy settings",
      (result as ExecutionValidation.Failed).message
    )
  }

  @Test
  fun `should return failed when process execution throws exception`() {
    // Given
    val binaryPath = "/path/to/binary"
    val errorMessage = "Permission denied"

    every { pathValidator.validateBinaryFile(binaryPath) } returns PathValidation.Success
    every { processExecutor.executeProcess(any()) } throws RuntimeException(errorMessage)

    // When
    val result = validator.validateBinaryExecution(binaryPath)

    // Then
    assertInstanceOf(ExecutionValidation.Failed::class.java, result)
    assertEquals("Permission denied - check file permissions", (result as ExecutionValidation.Failed).message)
  }

  @Test
  fun `should verify command line is created with correct parameters`() {
    // Given
    val binaryPath = "/path/to/binary"
    val versionOutput = "tinymist 401.293.193"
    var capturedCommandLine: GeneralCommandLine? = null

    every { pathValidator.validateBinaryFile(binaryPath) } returns PathValidation.Success
    every { processExecutor.executeProcess(capture(slot())) } answers {
      capturedCommandLine = firstArg()
      ProcessOutput(versionOutput, "", 0, false, false)
    }

    // When
    validator.validateBinaryExecution(binaryPath)

    // Then
    assertEquals(binaryPath, capturedCommandLine?.exePath)
    assertEquals(listOf("-V"), capturedCommandLine?.parametersList?.list)
  }

  @ParameterizedTest
  @ValueSource(strings = ["0.11.12", "0.15.8"])
  fun `should accept valid custom binary versions without a minimum requirement`(version: String) {
    // Given
    val binaryPath = "/path/to/binary"
    val versionOutput = "tinymist $version"
    val (major, minor, patch) = version.split('.').map(String::toInt)
    val expectedVersion = Version(major, minor, patch)

    every { pathValidator.validateBinaryFile(binaryPath) } returns PathValidation.Success
    every { processExecutor.executeProcess(any()) } returns ProcessOutput(versionOutput, "", 0, false, false)

    // When
    val result = validator.validateBinaryExecution(binaryPath)

    // Then
    assertInstanceOf(ExecutionValidation.Success::class.java, result)
    assertEquals(expectedVersion, (result as ExecutionValidation.Success).version)
  }
} 
