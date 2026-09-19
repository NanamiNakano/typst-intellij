package dev.thynanami.idea.typst

import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import java.nio.file.Path

class TypstTextMateBundleProvider : TextMateBundleProvider, PluginAware {
    private lateinit var pluginPath: Path

    override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
        pluginPath = pluginDescriptor.pluginPath
    }

    override fun getBundles(): List<TextMateBundleProvider.PluginBundle> =
        listOf(TypstFileType, TypstCodeFileType).map { fileType ->
            TextMateBundleProvider.PluginBundle(
                fileType.name,
                pluginPath.resolve(BuildConfig.TEXTMATE_DIRECTORY).resolve(fileType.textMateBundleName),
            )
        }
}
