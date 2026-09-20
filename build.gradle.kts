import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import com.github.gradle.node.task.NodeTask
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.jetbrains.intellij.platform.gradle.extensions.intellijPlatform
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("java")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intelliJPlatform)
    alias(libs.plugins.changelog)
    alias(libs.plugins.qodana)
    alias(libs.plugins.buildConfig)
    alias(libs.plugins.node)
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
        into(layout.buildDirectory.dir("native/$rustTarget"))
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

node {
    version = providers.gradleProperty("nodeVersion")
    download = true
    workDir = layout.buildDirectory.dir("nodejs")
}

val textMateSources = layout.projectDirectory.dir("vendor/tinymist/syntaxes/textmate")
val textMateGeneratorDirectory = layout.buildDirectory.dir("textmate/generator")

val stageTextMateGenerator = tasks.register<Sync>("stageTextMateGenerator") {
    description = "Stage the vendored Typst TextMate generator, restricted to look-behinds Joni can compile"
    from(textMateSources) { include("*.ts") }
    filesMatching("feature.ts") {
        filter { line ->
            line.replace("FIXED_LENGTH_LOOK_BEHIND = false", "FIXED_LENGTH_LOOK_BEHIND = true")
        }
    }
    into(textMateGeneratorDirectory)
}

val generateTextMateGrammars = tasks.register<NodeTask>("generateTextMateGrammars") {
    description = "Generate the Typst TextMate grammars"
    inputs.files(stageTextMateGenerator)
    workingDir = textMateGeneratorDirectory
    script = textMateGeneratorDirectory.map { it.file("main.ts") }
    options = listOf(
        "--eval",
        "import('./main.ts').then((generator) => generator.generate())",
    )
    outputs.files(
        textMateGeneratorDirectory.map { it.file("typst.tmLanguage.json") },
        textMateGeneratorDirectory.map { it.file("typst-code.tmLanguage.json") },
    )
}

val vscodeExtension = layout.projectDirectory.dir("vendor/tinymist/editors/vscode")
val generatedGrammarPrefix = "./out/"

val textMateBundle = tasks.register("textMateBundle") {
    description = "Assemble one single-grammar TextMate bundle per Typst language"
    val vscodeRoot = vscodeExtension
    val vscodeManifest = vscodeExtension.file("package.json")
    val grammarDirectory = textMateGeneratorDirectory
    val bundleRoot = layout.buildDirectory.dir("textmate/bundle")
    val bundleVersion = tinymistVersion
    val grammarPrefix = generatedGrammarPrefix
    inputs.file(vscodeManifest)
    inputs.dir(vscodeExtension.dir("syntaxes"))
    inputs.files(generateTextMateGrammars)
    outputs.dir(bundleRoot)

    doLast {
        bundleRoot.get().asFile.deleteRecursively()

        @Suppress("UNCHECKED_CAST")
        val contributes = (JsonSlurper().parse(vscodeManifest.asFile) as Map<String, Any>)
            .getValue("contributes") as Map<String, List<Map<String, Any>>>

        fun bundleRelative(path: Any?) = "./" + File(path.toString()).name

        val languagesById = contributes.getValue("languages").associateBy { it.getValue("id") }

        contributes.getValue("grammars")
            .filter { it.getValue("path").toString().startsWith(grammarPrefix) }
            .forEach { grammar ->
                val languageId = grammar.getValue("language").toString()
                val language = languagesById.getValue(languageId)
                val configuration = language.getValue("configuration").toString()
                val grammarName = File(grammar.getValue("path").toString()).name
                val extensions = (language.getValue("extensions") as List<*>)
                    .map { it.toString().removePrefix(".") }
                val bundle = bundleRoot.get().dir(languageId).apply { asFile.mkdirs() }

                bundle.file("package.json").asFile.writeText(
                    JsonOutput.prettyPrint(
                        JsonOutput.toJson(
                            mapOf(
                                "name" to languageId,
                                "version" to bundleVersion,
                                "contributes" to mapOf(
                                    "languages" to listOf(
                                        language - "icon" + ("configuration" to bundleRelative(configuration))
                                    ),
                                    "grammars" to listOf(
                                        grammar + ("path" to bundleRelative(grammar.getValue("path")))
                                    ),
                                ),
                            )
                        )
                    )
                )

                bundle.file(File(configuration).name).asFile
                    .writeBytes(vscodeRoot.file(configuration.removePrefix("./")).asFile.readBytes())

                val declaredFileTypes = """"fileTypes":${JsonOutput.toJson(extensions)},"""
                bundle.file(grammarName).asFile.writeText(
                    grammarDirectory.get().file(grammarName).asFile.readText()
                        .replaceFirst("{", "{$declaredFileTypes")
                )
            }
    }
}

tasks.withType<PrepareSandboxTask>().configureEach {
    from(textMateBundle) { into(pluginName.map { "$it/textmate" }) }
}

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
    buildConfigField("TEXTMATE_DIRECTORY", "textmate")
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
        // Avoid the coroutine agent's SIGTRAP dump loop with in-process JCEF.
        coroutinesJavaAgentFile.unset()
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
