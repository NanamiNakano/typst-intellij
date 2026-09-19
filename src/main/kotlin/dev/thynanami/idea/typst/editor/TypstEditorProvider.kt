package dev.thynanami.idea.typst.editor

import dev.thynanami.idea.typst.isTypstMarkupFile
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class TypstEditorProvider : FileEditorProvider, DumbAware {
  override fun accept(project: Project, file: VirtualFile): Boolean {
    return file.isTypstMarkupFile()
  }

  override fun createEditor(project: Project, file: VirtualFile): FileEditor {
    return TypstSplitEditor(project, file)
  }

  override fun getEditorTypeId(): String = "typst-split-editor"

  override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
