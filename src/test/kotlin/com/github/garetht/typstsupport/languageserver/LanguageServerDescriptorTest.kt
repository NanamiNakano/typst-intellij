package com.github.garetht.typstsupport.languageserver

import com.github.garetht.typstsupport.getMockedProject
import com.github.garetht.typstsupport.mockIntelliJEnvironment
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.encoding.EncodingManager
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class LanguageServerDescriptorTest {
  @BeforeEach
  fun setup() {
    mockIntelliJEnvironment {
      application {
        customize {
          every { getService(EncodingManager::class.java) } returns mockk {
            every { defaultConsoleEncoding } returns Charsets.UTF_8
          }
        }
      }
    }
  }

  @AfterEach
  fun teardown() {
    unmockkAll()
  }

  @ParameterizedTest
  @ValueSource(strings = ["typ"])
  fun shouldSupportTypstFileTypes(fileName: String) {
    // Arrange


    val descriptor = TinymistLanguageServerDescriptor(
      Path.of(""),
      getMockedProject()
    )

    val mockFile = mockk<VirtualFile>(relaxed = true) {
      every { extension } returns fileName
    }

    // Act
    val isFileSupported = descriptor.isSupportedFile(mockFile)

    // Assert
    assertTrue(isFileSupported)
  }

  @ParameterizedTest
  @ValueSource(strings = ["txt", "java", "py", "js", "json"])
  fun shouldNotSupportOtherExtensions(fileName: String) {
    // Arrange
    val descriptor = TinymistLanguageServerDescriptor(
      Path.of(""),
      getMockedProject()
    )

    val mockFile = mockk<VirtualFile>(relaxed = true) {
      every { extension } returns fileName
    }

    // Act
    val isFileSupported = descriptor.isSupportedFile(mockFile)

    // Assert
    assertFalse(isFileSupported)
  }

  @ParameterizedTest
  @CsvSource(
    "typ, false, false, true",
    "typ, true, false, true",
    "txt, false, false, false",
    "txt, true, false, false",
    "txt, false, true, true",
    "txt, true, true, true",
  )
  fun shouldPreserveExclusiveServerFormattingPolicy(
    fileExtension: String,
    ideCanFormat: Boolean,
    serverRequestsFormatting: Boolean,
    expected: Boolean,
  ) {
    val descriptor = TinymistLanguageServerDescriptor(Path.of(""), getMockedProject())
    val mockFile = mockk<VirtualFile> {
      every { extension } returns fileExtension
    }
    val formattingSupport = descriptor.lspCustomization.formattingCustomizer as LspFormattingSupport

    assertEquals(
      expected,
      formattingSupport.shouldFormatThisFileExclusivelyByServer(mockFile, ideCanFormat, serverRequestsFormatting),
    )
  }

  @Test
  fun shouldCreateCommandLineFromPath() {
    // Arrange
    val path = Path.of("/" + UUID.randomUUID())
    val descriptor = TinymistLanguageServerDescriptor(
      path,
      getMockedProject()
    )

    // Act
    val command = descriptor.createCommandLine()

    // Assert
    assertEquals(path.toString(), command.commandLineString)
  }
}
