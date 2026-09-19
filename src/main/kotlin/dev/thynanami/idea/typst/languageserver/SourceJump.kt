package dev.thynanami.idea.typst.languageserver

data class SourceJump(
  val filepath: String,
  val start: List<Int>? = null,
  val end: List<Int>? = null
)
