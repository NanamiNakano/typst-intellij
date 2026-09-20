package dev.thynanami.idea.typst.typm

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectId
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemProjectTracker
import com.intellij.openapi.externalSystem.autoimport.ExternalSystemRefreshStatus
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.vfs.newvfs.events.VFileMoveEvent
import com.intellij.openapi.vfs.newvfs.events.VFilePropertyChangeEvent
import dev.thynanami.idea.typst.lsp.TinymistLanguageServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private val typmSystemId = ProjectSystemId("TYPM", "Typm")

@Service(Service.Level.PROJECT)
class TypmProjectService(private val project: Project, private val scope: CoroutineScope) : Disposable {
    private val layout = TypmProjectLayout(project.basePath)
    private val initialized = AtomicBoolean()
    private val syncing = AtomicBoolean()
    private val presenceMutex = Mutex()
    private var presenceInitialized = false
    @Volatile private var projectAware: TypmProjectAware? = null

    suspend fun initialize() {
        if (project.isDefault || !initialized.compareAndSet(false, true)) return
        val manifest = layout.manifest ?: return
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(
                VirtualFileManager.VFS_CHANGES,
                TypmManifestFileListener(this, FileUtil.toSystemIndependentName(manifest.toString())),
            )
        refreshPresence()
    }

    fun settingsChanged() {
        scope.launch {
            initialize()
            withContext(Dispatchers.EDT) {
                projectAware?.let { aware ->
                    val tracker = ExternalSystemProjectTracker.getInstance(project)
                    tracker.markDirty(aware.projectId)
                    tracker.scheduleChangeProcessing()
                }
            }
        }
    }

    fun manifestPresenceChanged() {
        scope.launch { refreshPresence() }
    }

    private suspend fun refreshPresence() {
        presenceMutex.withLock {
            val exists = withContext(Dispatchers.IO) { layout.exists() }
            val changed = withContext(Dispatchers.EDT) {
                if (project.isDisposed) return@withContext false
                val wasPresent = projectAware != null
                if (exists != wasPresent) {
                    val tracker = ExternalSystemProjectTracker.getInstance(project)
                    if (exists) {
                        val root = requireNotNull(layout.root)
                        val manifest = requireNotNull(layout.manifest)
                        val aware = TypmProjectAware(
                            ExternalSystemProjectId(typmSystemId, FileUtil.toSystemIndependentName(root.toString())),
                            FileUtil.toSystemIndependentName(manifest.toString()),
                            this@TypmProjectService,
                        )
                        projectAware = aware
                        tracker.register(aware, this@TypmProjectService)
                        tracker.activate(aware.projectId)
                    } else {
                        projectAware?.let { tracker.remove(it.projectId) }
                        projectAware = null
                    }
                }
                val restart = presenceInitialized && exists != wasPresent
                presenceInitialized = true
                restart
            }
            if (changed) {
                try {
                    project.service<TinymistLanguageServer>().restartAndRestorePreviews()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cancelled: ProcessCanceledException) {
                    throw cancelled
                } catch (error: Exception) {
                    logger<TypmProjectService>().warn("Could not restart Tinymist after Typm detection changed", error)
                    notifyFailure(error, "Could not restart Tinymist")
                }
            }
        }
    }

    fun sync() {
        val aware = projectAware ?: return
        if (!syncing.compareAndSet(false, true)) return
        scope.launch {
            var status = ExternalSystemRefreshStatus.FAILURE
            var started = false
            try {
                // The platform snapshots settings at reload start, including on-disk content.
                saveManifest()
                withContext(Dispatchers.EDT) {
                    aware.reloadStarted()
                    started = true
                }
                TypmSyncRunner().sync(project, layout, project.service<TypmProjectSettings>().executablePath)
                refreshGeneratedFiles()
                project.service<TinymistLanguageServer>().restartAndRestorePreviews()
                status = ExternalSystemRefreshStatus.SUCCESS
            } catch (cancelled: CancellationException) {
                status = ExternalSystemRefreshStatus.CANCEL
                throw cancelled
            } catch (cancelled: ProcessCanceledException) {
                status = ExternalSystemRefreshStatus.CANCEL
                throw cancelled
            } catch (error: Exception) {
                logger<TypmProjectService>().warn("Typm sync failed", error)
                notifyFailure(error)
            } finally {
                try {
                    withContext(NonCancellable + Dispatchers.EDT) {
                        if (started) aware.reloadFinished(status)
                        if (!project.isDisposed && projectAware != null) {
                            val tracker = ExternalSystemProjectTracker.getInstance(project)
                            if (projectAware === aware && hasUnsavedManifestChanges()) {
                                tracker.markDirty(aware.projectId)
                            }
                            tracker.scheduleChangeProcessing()
                        }
                    }
                } finally {
                    syncing.set(false)
                }
            }
        }
    }

    private suspend fun saveManifest() {
        val manifest = requireNotNull(layout.manifest)
        val file = withContext(Dispatchers.IO) {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(manifest)
        } ?: error("typm.toml no longer exists in this project.")
        withContext(Dispatchers.EDT) {
            val manager = FileDocumentManager.getInstance()
            manager.getCachedDocument(file)?.let { document ->
                manager.saveDocument(document)
                check(!manager.isDocumentUnsaved(document)) { "Save typm.toml before syncing packages." }
            }
        }
    }

    private fun hasUnsavedManifestChanges(): Boolean {
        val manifest = layout.manifest ?: return false
        val file = LocalFileSystem.getInstance().findFileByNioFile(manifest) ?: return false
        val manager = FileDocumentManager.getInstance()
        val document = manager.getCachedDocument(file) ?: return false
        return manager.isDocumentUnsaved(document)
    }

    private suspend fun refreshGeneratedFiles() {
        val root = layout.root ?: return
        withContext(Dispatchers.IO) {
            VfsUtil.markDirtyAndRefresh(false, true, true, root.resolve(".typm"), root.resolve("typm.lock"))
        }
    }

    private suspend fun notifyFailure(error: Exception, title: String = "Typm sync failed") {
        withContext(Dispatchers.EDT) {
            if (project.isDisposed) return@withContext
            val notification = NotificationGroupManager.getInstance().getNotificationGroup("Typst")
                .createNotification(
                    title,
                    StringUtil.escapeXmlEntities(error.message ?: "Could not synchronize Typm packages."),
                    NotificationType.ERROR,
                )
            if (error is TypmExecutableException) notification.addAction(TypmOpenSettingsAction())
            notification.notify(project)
        }
    }

    override fun dispose() = Unit
}

private class TypmManifestFileListener(
    private val service: TypmProjectService,
    private val manifestPath: String,
) : BulkFileListener {
    override fun after(events: List<VFileEvent>) {
        if (events.any(::affectsManifestPresence)) service.manifestPresenceChanged()
    }

    private fun affectsManifestPresence(event: VFileEvent): Boolean {
        if (event is VFileContentChangeEvent) return false
        val paths = when (event) {
            is VFileMoveEvent -> listOf(event.oldPath, event.newPath)
            is VFilePropertyChangeEvent -> listOf(event.oldPath, event.newPath)
            else -> listOf(event.path)
        }
        return paths.any { path -> path == manifestPath || manifestPath.startsWith("$path/") }
    }
}

private class TypmOpenSettingsAction : NotificationAction("Configure typm") {
    override fun actionPerformed(event: AnActionEvent, notification: Notification) {
        val project = event.project ?: return
        ShowSettingsUtil.getInstance().showSettingsDialog(project, TypmSettingsConfigurable::class.java)
    }
}
