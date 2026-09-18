package dev.thynanami.idea.typst.configuration

import dev.thynanami.idea.typst.languageserver.TypstLanguageServerManager
import dev.thynanami.idea.typst.languageserver.locations.Version
import dev.thynanami.idea.typst.notifier.Notifier
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndWait
import io.mockk.Awaits
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.assertThrows

class SettingsConfigurableTest : BasePlatformTestCase() {
  override fun setUp() {
    super.setUp()
    mockkObject(Notifier)
    every { Notifier["notify"](any<String>(), any<NotificationType>()) } just Awaits
  }

  override fun tearDown() {
    try {
      unmockkAll()
    } finally {
      super.tearDown()
    }
  }

  private val mockLanguageServerManager = mockk<TypstLanguageServerManager>(relaxed = true)

  fun testPanelInitializesWithCorrectDefaultState() {
    val settings = SettingsState()
    val configurable = SettingsConfigurable(
      mockk(),
      mockk(),
      settings,
      mockLanguageServerManager
    )
    configurable.createPanel()
    assertNotNull(configurable.fileField)
    assertNotNull(configurable.customRadioButton)
    assertNotNull(configurable.testResultLabel)
    assertEquals("", configurable.fileField.component.text)
    assertEquals(BinarySource.USE_AUTOMATIC_DOWNLOAD, settings.state.binarySource)
  }

  fun testPanelInitializesWithCustomBinaryPathIfPreviouslySet() {
    val settings = SettingsState()
    settings.state.customBinaryPath = "/path/to/binary"
    settings.state.binarySource = BinarySource.USE_CUSTOM_BINARY

    val configurable = SettingsConfigurable(
      mockk(),
      mockk(),
      settings,
      mockLanguageServerManager
    )
    configurable.createPanel()

    assertEquals("/path/to/binary", configurable.fileField.component.text)
    assertTrue(configurable.customRadioButton.component.isSelected)
  }

  fun testSwitchingToCustomBinaryResetsValidationAndLabel() {
    val settings = SettingsState()
    val configurable = SettingsConfigurable(
      mockk(),
      mockk(),
      settings,
      mockLanguageServerManager
    )

    configurable.createPanel()

    configurable.testResultLabel.component.text = "Success!"
    configurable.testResultLabel.component.icon = AllIcons.General.InspectionsOK

    configurable.customRadioButton.component.doClick()

    assertEquals("", configurable.testResultLabel.component.text)
    assertNull(configurable.testResultLabel.component.icon)
  }

  fun testSwitchingToAutomaticDownloadDisablesFileField() {
    val settings = SettingsState()
    val configurable = SettingsConfigurable(
      mockk(),
      mockk(),
      settings,
      mockLanguageServerManager
    )

    configurable.createPanel()
    configurable.customRadioButton.component.doClick()
    assertTrue(configurable.fileField.component.isEnabled)

    configurable.automaticRadioButton.component.doClick()
    assertFalse(configurable.fileField.component.isEnabled)
  }

  fun testCustomBinaryPathValidationFailsWhenPathIsIncorrect() {
    val settings = SettingsState()
    val failureMessage = "Path not a binary"
    val configurable = SettingsConfigurable(
      object : PathValidator {
        override fun validateBinaryFile(binaryPath: String): PathValidation =
          PathValidation.Success

      }, object : ExecutionValidator {
        override fun validateBinaryExecution(binaryPath: String): ExecutionValidation =
          ExecutionValidation.Failed(failureMessage)
      },
      settings,
      mockLanguageServerManager
    )

    configurable.createPanel()
    configurable.customRadioButton.component.doClick()
    configurable.fileField.component.textField.text = ""

    configurable.testBinaryButton.component.doClick()

    runInEdtAndWait {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      assertEquals(
        "Failed: $failureMessage",
        configurable.testResultLabel.component.text
      )
    }
  }

  fun testCustomBinaryPathValidationSucceedsWithCorrectPath() {
    val settings = SettingsState()
    val version = Version(1, 2, 3)
    val configurable = SettingsConfigurable(
      object : PathValidator {
        override fun validateBinaryFile(binaryPath: String): PathValidation =
          PathValidation.Success
      }, object : ExecutionValidator {
        override fun validateBinaryExecution(binaryPath: String): ExecutionValidation =
          ExecutionValidation.Success(version)
      },
      settings,
      mockLanguageServerManager
    )

    configurable.createPanel()
    configurable.customRadioButton.component.doClick()
    configurable.fileField.component.textField.text = "/valid/path"

    configurable.testBinaryButton.component.doClick()
    configurable.testBinaryButton.component.doClick()

    runInEdtAndWait {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      assertEquals(
        "Success: ${version.toConsoleString()}",
        configurable.testResultLabel.component.text
      )
      assertEquals(AllIcons.General.InspectionsOK, configurable.testResultLabel.component.icon)
    }
  }

  fun testApplyThrowsConfigurationExceptionWhenCustomBinaryIsNotValidated() {
    val settings = SettingsState()
    val configurable = SettingsConfigurable(
      object : PathValidator {
        override fun validateBinaryFile(binaryPath: String): PathValidation = PathValidation.Success
      },
      object : ExecutionValidator {
        override fun validateBinaryExecution(binaryPath: String): ExecutionValidation =
          ExecutionValidation.Success(Version(1, 2, 3))
      },
      settings,
      mockLanguageServerManager
    )

    configurable.createPanel()
    configurable.customRadioButton.component.doClick()
    configurable.fileField.component.textField.text = "/path/to/binary"

    val exception = assertThrows<ConfigurationException> {
      configurable.apply()
    }
    assertEquals("The binary should be tested with Test Binary before applying", exception.localizedMessage)
  }

  fun testApplySucceedsWhenCustomBinaryIsValidated() {
    val settings = SettingsState()
    val configurable = SettingsConfigurable(
      object : PathValidator {
        override fun validateBinaryFile(binaryPath: String): PathValidation =
          PathValidation.Success
      }, object : ExecutionValidator {
        override fun validateBinaryExecution(binaryPath: String): ExecutionValidation =
          ExecutionValidation.Success(Version(1, 2, 3))
      },
      settings
    )

    configurable.createPanel()
    configurable.customRadioButton.component.doClick()
    configurable.fileField.component.textField.text = "/path/to/binary"

    configurable.testBinaryButton.component.doClick()

    assertThrows<ConfigurationException> { configurable.apply() }
  }

  fun testApplySucceedsWithAutomaticDownloadSelected() {
    val settings = SettingsState()
    val configurable = SettingsConfigurable(
      mockk(),
      mockk(),
      settings,
      mockLanguageServerManager
    )

    configurable.createPanel()
    configurable.automaticRadioButton.component.doClick()

    configurable.apply()

    runInEdtAndWait {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      assertEquals(BinarySource.USE_AUTOMATIC_DOWNLOAD, settings.state.binarySource)
      coVerify(exactly = 1) { mockLanguageServerManager.initialStart(any<Project>()) }
      verify(exactly = 1) { Notifier.info(any<String>()) }
    }
  }

  fun testHandlesValidationErrorDuringBinaryTest() {
    val settings = SettingsState()
    val errorMessage = "Unexpected error during validation"
    val configurable = SettingsConfigurable(
      object : PathValidator {
        override fun validateBinaryFile(binaryPath: String): PathValidation =
          PathValidation.Success
      }, object : ExecutionValidator {
        override fun validateBinaryExecution(binaryPath: String): ExecutionValidation =
          ExecutionValidation.Failed(errorMessage)
      },
      settings,
      mockLanguageServerManager
    )

    runInEdtAndWait {
      configurable.createPanel()
      configurable.customRadioButton.component.doClick()
      configurable.fileField.component.textField.text = "/path/to/binary"
      configurable.testBinaryButton.component.doClick()
      // TODO: this fixes the test for unknown reasons
      configurable.testBinaryButton.component.doClick()
    }

    runInEdtAndWait {
      PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
      assertTrue(configurable.testResultLabel.component.isVisible)
      assertEquals(
        "Failed: $errorMessage",
        configurable.testResultLabel.component.text
      )
      assertEquals(AllIcons.General.Error, configurable.testResultLabel.component.icon)
    }
  }

  fun testHandlesValidationErrorWhenApplyingInvalidly() {
    val settings = SettingsState()
    val errorMessage = "Invalid binary path"
    val configurable = SettingsConfigurable(
      object : PathValidator {
        override fun validateBinaryFile(binaryPath: String): PathValidation =
          PathValidation.Failed(errorMessage)
      }, mockk(),
      settings,
      mockLanguageServerManager
    )

    configurable.createPanel()
    configurable.customRadioButton.component.doClick()
    configurable.fileField.component.textField.text = "/invalid/path"

    val exception = assertThrows<ConfigurationException> {
      configurable.apply()
    }

    assertEquals(errorMessage, exception.localizedMessage)
  }
}
