package dev.thynanami.idea.typst.configuration

enum class TypstFormatter {
  TYPSTFMT,
  TYPSTYLE;

  override fun toString(): String = when (this) {
    TYPSTFMT -> "typstfmt"
    TYPSTYLE -> "typstyle"
  }
}
