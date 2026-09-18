import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
    alias(libs.plugins.changelog)
    alias(libs.plugins.qodana)
    alias(libs.plugins.buildConfig)
}

group = providers.gradleProperty("pluginGroup").get()

version = providers.gradleProperty("pluginVersion").get()

val tinymistVersion = providers.gradleProperty("tinymistVersion").get()

kotlin { jvmToolchain(25) }

repositories {
    mavenCentral()

    exclusiveContent {
        forRepository {
            ivy {
                name = "Tinymist Releases"
                url = uri("https://github.com/Myriad-Dreamin/tinymist/releases/download")
                patternLayout { artifact("v[revision]/[artifact]-[classifier].[ext]") }
                metadataSources { artifact() }
            }
        }
        filter { includeModule("tinymist", "tinymist") }
    }

    intellijPlatform { defaultRepositories() }
}

fun downloadTinymist(rustTarget: String): TaskProvider<Sync> {
    val windows = "windows" in rustTarget
    val archiveExtension = if (windows) "zip" else "tar.gz"

    val releaseArchive = configurations.create("tinymist-$rustTarget") {
        isCanBeConsumed = false
        isTransitive = false
    }
    dependencies.add(
        releaseArchive.name,
        "tinymist:tinymist:$tinymistVersion:$rustTarget@$archiveExtension",
    )
    val archiveFiles = releaseArchive.incoming.files
    val archive = providers.provider { archiveFiles.singleFile }

    return tasks.register<Sync>("downloadTinymist-$rustTarget") {
        description = "Download tinymist (${rustTarget})"
        from(if (windows) zipTree(archive) else tarTree(resources.gzip(archive))) {
            include("**/tinymist", "**/tinymist.exe")
            eachFile { relativePath = RelativePath(true, "bin", name) }
        }
        includeEmptyDirs = false
        into(layout.projectDirectory.dir("native/$rustTarget"))
    }
}

val tinymistDownloads = listOf(
    "aarch64-apple-darwin",
    "aarch64-pc-windows-msvc",
    "aarch64-unknown-linux-gnu",
    "x86_64-apple-darwin",
    "x86_64-pc-windows-msvc",
    "x86_64-unknown-linux-gnu",
).associateWith(::downloadTinymist)

dependencies {
    intellijPlatform {
        create(providers.gradleProperty("platformType"), providers.gradleProperty("platformVersion"))
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })
    }
}

intellijPlatform {
    nativeVariants {
        enabled = true

        linux {
            x86_64.from(tinymistDownloads.getValue("x86_64-unknown-linux-gnu"))
            arm64.from(tinymistDownloads.getValue("aarch64-unknown-linux-gnu"))
        }
        mac {
            x86_64.from(tinymistDownloads.getValue("x86_64-apple-darwin"))
            arm64.from(tinymistDownloads.getValue("aarch64-apple-darwin"))
        }
        windows {
            x86_64.from(tinymistDownloads.getValue("x86_64-pc-windows-msvc"))
            arm64.from(tinymistDownloads.getValue("aarch64-pc-windows-msvc"))
        }
    }

    pluginVerification {
        ides {
            create("IU", "2026.2")
            current()
        }
    }

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException(
                        "Plugin description section not found in README.md:\n$start ... $end"
                    )
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased()).withHeader(false).withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        channels = providers.gradleProperty("pluginVersion").map {
            listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" })
        }
    }
}

buildConfig {
    packageName("dev.thynanami.idea.typst")
    buildConfigField("TINYMIST_VERSION", tinymistVersion)
    buildConfigField("TINYMIST_DIRECTORY", "bin")
}

changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
}

tasks {
    wrapper { gradleVersion = providers.gradleProperty("gradleVersion").get() }

    publishPlugin {
        dependsOn(patchChangelog)
        token = System.getenv("PUBLISH_TOKEN")
    }

    runIde {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Djcef.remote.enabled=false",
            )
        }
        systemProperty(
            "idea.log.trace.categories",
            "dev.thynanami.idea.typst",
        )
    }
}
