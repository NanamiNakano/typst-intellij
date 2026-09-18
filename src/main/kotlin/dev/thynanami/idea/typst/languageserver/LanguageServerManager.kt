package dev.thynanami.idea.typst.languageserver

import com.intellij.openapi.project.Project

interface LanguageServerManager {
  suspend fun initialStart(project: Project)
}
