package dev.thynanami.idea.typst.editor

import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

class TypstSplitEditor(project: Project, file: VirtualFile) :
  TextEditorWithPreview(
    TextEditorProvider.getInstance().createEditor(project, file) as TextEditor,
    TypstPreviewEditor(project, file),
    "TypstSplitEditor",
  )
