package dev.thynanami.idea.typst.languageserver.locations

import dev.thynanami.idea.typst.mockIntelliJEnvironment
import com.github.stefanbirkner.systemlambda.SystemLambda.restoreSystemProperties
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.URI
import java.nio.file.Path
import java.util.UUID
import java.util.stream.Stream
import kotlin.streams.asStream

class TinymistLocationResolverTest {

  @AfterEach
  fun `Remove all mocks`() {
    unmockkAll()
  }

  @ParameterizedTest
  @MethodSource("generateUrlArgs")
  fun shouldGetUrl(osName: String, archName: String, expectedUrl: URI) {
    restoreSystemProperties {
      System.setProperty("os.name", osName)
      System.setProperty("os.arch", archName)

      mockIntelliJEnvironment {
        appDirs {
          this.userDataDir = ""
        }
      }

      val resolver = TinymistLocationResolver()

      val url = resolver.downloadUrl()

      Assertions.assertEquals(expectedUrl, url)
    }

  }

  @Test
  fun shouldRestoreJnaNoClassPath() {
    restoreSystemProperties {
      System.setProperty("jna.noclasspath", "true")

      mockIntelliJEnvironment {
        appDirs {
          this.userDataDir = ""
        }
      }


      val resolver = TinymistLocationResolver()
      resolver.binaryPath()

      Assertions.assertEquals(System.getProperty("jna.noclasspath"), "true")
    }

  }

  @Test
  fun shouldRestoreJnaNoClassPathNull() {
    restoreSystemProperties {
      System.clearProperty("jna.noclasspath")

      mockIntelliJEnvironment {
        appDirs {
          this.userDataDir = ""
        }
      }
      val resolver = TinymistLocationResolver()
      resolver.binaryPath()

      Assertions.assertEquals(System.getProperty("jna.noclasspath"), null)
    }

  }

  @ParameterizedTest
  @MethodSource("generatePathArgs")
  fun shouldGetPath(basePath: String, osName: String, expectedPath: String) {
    restoreSystemProperties {
      System.setProperty("os.name", osName)

      mockIntelliJEnvironment {
        appDirs {
          this.userDataDir = basePath
        }
      }

      val resolver = TinymistLocationResolver()

      val path = resolver.binaryPath()

      Assertions.assertEquals(Path.of(expectedPath), path)
    }

  }

  companion object {

    data class OperatingSystem(
      val osNameProperty: String,
      val platformId: String,
      val downloadExtension: String = "",
      val executableExtension: String = ""
    )

    data class Architecture(
      val osArchProperty: String,
      val platformId: String
    )

    data class TestConfiguration(
      val os: OperatingSystem,
      val architecture: Architecture,
      val expectedUrl: URI
    )

    private val supportedOperatingSystems = listOf(
      OperatingSystem(
        osNameProperty = "Windows",
        platformId = "pc-windows-msvc",
        downloadExtension = ".zip",
        executableExtension = ".exe"
      ),
      OperatingSystem(
        osNameProperty = "MacOs X",
        platformId = "apple-darwin",
        downloadExtension = ".tar.gz",

        ),
      OperatingSystem(
        osNameProperty = UUID.randomUUID().toString(),
        platformId = "unknown-linux-gnu",
        downloadExtension = ".tar.gz",
      )
    )

    private val supportedArchitectures = listOf(
      Architecture(
        osArchProperty = "arch64",
        platformId = "aarch64"
      ),
      Architecture(
        osArchProperty = UUID.randomUUID().toString(),
        platformId = "x86_64"
      )
    )

    @JvmStatic
    fun generateUrlArgs(): Stream<Arguments> {
      return generateTestConfigurations()
        .map { config ->
          Arguments.of(
            config.os.osNameProperty,
            config.architecture.osArchProperty,
            config.expectedUrl
          )
        }
        .asStream()
    }

    private fun generateTestConfigurations(): Sequence<TestConfiguration> = sequence {
      for (os in supportedOperatingSystems) {
        for (architecture in supportedArchitectures) {
          val expectedUrl = buildDownloadUrl(
            platformId = os.platformId,
            architectureId = architecture.platformId,
            downloadExtension = os.downloadExtension
          )

          yield(
            TestConfiguration(
              os = os,
              architecture = architecture,
              expectedUrl = expectedUrl
            )
          )
        }
      }
    }

    private fun buildDownloadUrl(
      platformId: String,
      architectureId: String,
      downloadExtension: String
    ): URI {
      val urlTemplate = "https://github.com/Myriad-Dreamin/tinymist/releases/download/v0.15.8/tinymist-%s-%s%s"
      return URI(urlTemplate.format(architectureId, platformId, downloadExtension))
    }

    @JvmStatic
    fun generatePathArgs(): Stream<Arguments> {
      val seq = sequence {
        supportedOperatingSystems.forEach { osOpt ->
          val basePath = "/%s".format(UUID.randomUUID().toString())
          val expectedPath = "%s/language-server/v0.15.8/tinymist%s".format(
            basePath,
            osOpt.executableExtension
          )
          yield(
            Arguments.of(
              basePath,
              osOpt.osNameProperty,
              expectedPath
            )
          )
        }
      }

      return seq.asStream()
    }
  }
}
