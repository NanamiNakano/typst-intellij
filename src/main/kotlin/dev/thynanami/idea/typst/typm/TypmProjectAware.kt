package dev.thynanami.idea.typst.typm

import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectAware
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectId
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectListener
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectReloadContext
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemRefreshStatus
import com.intellij.util.EventDispatcher
import kotlin.jvm.JvmDefaultWithoutCompatibility

// Inherit platform defaults without generating bridges to its internal API methods.
@JvmDefaultWithoutCompatibility
class TypmProjectAware(
    override val projectId: ExternalSystemProjectId,
    manifestPath: String,
    private val service: TypmProjectService,
) : ExternalSystemProjectAware {
    private val listeners = EventDispatcher.create(ExternalSystemProjectListener::class.java)

    override val settingsFiles: Set<String> = setOf(manifestPath)

    override fun subscribe(listener: ExternalSystemProjectListener, parentDisposable: Disposable) {
        listeners.addListener(listener, parentDisposable)
    }

    @Suppress("UnstableApiUsage")
    override fun isDisabledAutoReload(context: ExternalSystemProjectReloadContext): Boolean = true

    override fun reloadProject(context: ExternalSystemProjectReloadContext) {
        service.sync()
    }

    fun reloadStarted() {
        listeners.multicaster.onProjectReloadStart()
    }

    fun reloadFinished(status: ExternalSystemRefreshStatus) {
        listeners.multicaster.onProjectReloadFinish(status)
    }
}
