package dev.thynanami.idea.typst.editor

import com.intellij.ide.AppLifecycleListener

private const val JCEF_REMOTE_PROPERTY = "jcef.remote.enabled"

internal class InProcessJcefInitializer : AppLifecycleListener {
    override fun appStarted() {
        System.setProperty(JCEF_REMOTE_PROPERTY, "false")
    }
}
