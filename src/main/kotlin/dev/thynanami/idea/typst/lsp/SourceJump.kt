package dev.thynanami.idea.typst.lsp

data class SourceJump(
  val filepath: String,
  val start: List<Int>? = null,
  val end: List<Int>? = null
)
