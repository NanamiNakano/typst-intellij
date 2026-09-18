package dev.thynanami.idea.typst

import dev.thynanami.idea.typst.configuration.SettingsState
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.PluginId
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import net.harawata.appdirs.AppDirs
import net.harawata.appdirs.AppDirsFactory

class IntelliJEnvironmentMockBuilder {
  private var pluginVersion: String? = null
  private var pluginId: String = "dev.thynanami.idea.typst"
  private var userDataDir: String? = null
  private var appName: String = "TypstSupport"
  private var appAuthor: String = "dev.thynanami"
  private var settingsState: SettingsState = SettingsState()
  private var customPluginSetup: (IdeaPluginDescriptor.() -> Unit)? = null
  private var customAppDirsSetup: (AppDirs.() -> Unit)? = null
  private var customSettingsSetup: (SettingsState.() -> Unit)? = null
  private var customApplicationSetup: (Application.() -> Unit)? = null

  fun plugin(block: PluginBuilder.() -> Unit): IntelliJEnvironmentMockBuilder {
    val builder = PluginBuilder()
    builder.block()
    this.pluginVersion = builder.version
    this.pluginId = builder.id
    this.customPluginSetup = builder.customSetup
    return this
  }

  fun appDirs(block: AppDirsBuilder.() -> Unit): IntelliJEnvironmentMockBuilder {
    val builder = AppDirsBuilder()
    builder.block()
    this.userDataDir = builder.userDataDir
    this.appName = builder.appName
    this.appAuthor = builder.appAuthor
    this.customAppDirsSetup = builder.customSetup
    return this
  }

  fun settingsState(block: SettingsStateBuilder.() -> Unit): IntelliJEnvironmentMockBuilder {
    val builder = SettingsStateBuilder()
    builder.block()
    this.settingsState = builder.mockInstance
    this.customSettingsSetup = builder.customSetup
    return this
  }

  fun application(block: ApplicationBuilder.() -> Unit): IntelliJEnvironmentMockBuilder {
    val builder = ApplicationBuilder()
    builder.block()
    this.customApplicationSetup = builder.customSetup
    return this
  }

  fun build(): MockContext {
    // Mock Plugin
    val pluginMock = mockk<IdeaPluginDescriptor> {
      pluginVersion?.let { version ->
        every { getVersion() } returns version
      }
      customPluginSetup?.invoke(this)
    }

    mockkStatic(PluginManagerCore::class)
    every {
      PluginManagerCore.getPlugin(
        match<PluginId> { it.idString == pluginId }
      )
    } returns pluginMock

    // Mock AppDirs
    val appDirsMock = mockk<AppDirs> {
      userDataDir?.let { dir ->
        every { getUserDataDir(appName, null, appAuthor) } returns dir
      }
      customAppDirsSetup?.invoke(this)
    }

    mockkStatic(AppDirsFactory::class)
    every { AppDirsFactory.getInstance() } returns appDirsMock

    // Mock Settings and Application
    val settingsStateMock = settingsState ?: mockk<SettingsState> {
      customSettingsSetup?.invoke(this)
    }

    val applicationMock = mockk<Application> {
      every { getService(SettingsState::class.java) } returns settingsStateMock
      customApplicationSetup?.invoke(this)
    }

    mockkStatic(ApplicationManager::class)
    every { ApplicationManager.getApplication() } returns applicationMock

    return MockContext(
      plugin = pluginMock,
      appDirs = appDirsMock,
      settingsState = settingsStateMock,
      application = applicationMock
    )
  }

  class PluginBuilder {
    var version: String? = null
    var id: String = "dev.thynanami.idea.typst"
    internal var customSetup: (IdeaPluginDescriptor.() -> Unit)? = null

    fun version(version: String): PluginBuilder {
      this.version = version
      return this
    }

    fun id(id: String): PluginBuilder {
      this.id = id
      return this
    }

    fun customize(setup: IdeaPluginDescriptor.() -> Unit): PluginBuilder {
      this.customSetup = setup
      return this
    }
  }

  class AppDirsBuilder {
    var userDataDir: String? = null
    var appName: String = "TypstSupport"
    var appAuthor: String = "dev.thynanami"
    internal var customSetup: (AppDirs.() -> Unit)? = null

    fun userDataDir(dir: String): AppDirsBuilder {
      this.userDataDir = dir
      return this
    }

    fun appName(name: String): AppDirsBuilder {
      this.appName = name
      return this
    }

    fun appAuthor(author: String): AppDirsBuilder {
      this.appAuthor = author
      return this
    }

    fun customize(setup: AppDirs.() -> Unit): AppDirsBuilder {
      this.customSetup = setup
      return this
    }
  }

  class SettingsStateBuilder {
    internal var mockInstance: SettingsState = SettingsState()
    internal var customSetup: (SettingsState.() -> Unit)? = null

    fun instance(settingsState: SettingsState): SettingsStateBuilder {
      this.mockInstance = settingsState
      return this
    }

    fun customize(setup: SettingsState.() -> Unit): SettingsStateBuilder {
      this.customSetup = setup
      return this
    }
  }

  class ApplicationBuilder {
    internal var customSetup: (Application.() -> Unit)? = null

    fun customize(setup: Application.() -> Unit): ApplicationBuilder {
      this.customSetup = setup
      return this
    }
  }

  companion object {
    fun create(): IntelliJEnvironmentMockBuilder = IntelliJEnvironmentMockBuilder()
  }
}

data class MockContext(
  val plugin: IdeaPluginDescriptor,
  val appDirs: AppDirs,
  val settingsState: SettingsState,
  val application: Application
) {
  fun cleanup() {
    unmockkStatic(PluginManagerCore::class)
    unmockkStatic(AppDirsFactory::class)
    unmockkStatic(ApplicationManager::class)
  }
}

// Extension function for even more idiomatic usage
fun mockIntelliJEnvironment(block: IntelliJEnvironmentMockBuilder.() -> Unit): MockContext {
  return IntelliJEnvironmentMockBuilder.create().apply(block).build()
}
