package dev.thynanami.idea.typst.run

import com.intellij.execution.Executor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key

private val typstRunProcess = Key.create<Boolean>("dev.thynanami.idea.typst.runProcess")

@Service(Service.Level.PROJECT)
internal class TypstRunLifecycle(private val project: Project) : Disposable {
    private var contentManager: RunContentManager? = null
    private var executor: Executor? = null

    fun register(handler: KillableColoredProcessHandler, executor: Executor) {
        // Resolve platform services while running, before plugin unloading forbids their creation.
        contentManager = RunContentManager.getInstance(project)
        this.executor = executor
        handler.putUserData(typstRunProcess, true)
    }

    override fun dispose() {
        val manager = contentManager ?: return
        val runExecutor = executor ?: return
        for (descriptor in manager.allDescriptors) {
            val handler = descriptor.processHandler as? KillableColoredProcessHandler ?: continue
            if (handler.getUserData(typstRunProcess) != true) continue
            handler.setShouldKillProcessSoftly(false)
            // During plugin unload's write action, the platform closes the tab and destroys
            // its process without prompting or waiting, releasing the execution environment too.
            manager.removeRunContent(runExecutor, descriptor)
        }
        contentManager = null
        executor = null
    }
}
