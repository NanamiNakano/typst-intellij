package dev.thynanami.idea.typst.config

enum class BinarySource {
    BUNDLED,
    CUSTOM
}

enum class TypstFormatter {
    TYPSTFMT,
    TYPSTYLE;

    override fun toString(): String = when (this) {
        TYPSTFMT -> "typstfmt"
        TYPSTYLE -> "typstyle"
    }
}
