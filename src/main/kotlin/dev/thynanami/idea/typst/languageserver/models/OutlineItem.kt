package dev.thynanami.idea.typst.languageserver.models

data class OutlineItem(
  val title: String,
  val span: String? = null,
  val position: DocumentPosition? = null,
  val children: List<OutlineItem> = emptyList()
)
