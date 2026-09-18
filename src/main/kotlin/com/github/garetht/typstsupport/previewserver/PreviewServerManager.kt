package com.github.garetht.typstsupport.previewserver

import com.intellij.openapi.project.Project
import java.util.concurrent.CompletableFuture

interface PreviewServerManager {
  fun start(filepath: String, project: Project): CompletableFuture<String>

  fun stop(filepath: String, project: Project)
}
