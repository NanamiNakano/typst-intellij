package dev.thynanami.idea.typst.typm

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class TypmProjectActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<TypmProjectService>().initialize()
    }
}
