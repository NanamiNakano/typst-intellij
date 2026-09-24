package dev.thynanami.idea.typst.lsp

import com.google.gson.annotations.SerializedName

enum class TinymistCompilationState {
    @SerializedName("compiling")
    COMPILING,

    @SerializedName("compileSuccess")
    SUCCESS,

    @SerializedName("compileError")
    ERROR,
}

data class TinymistCompileStatus(
    val status: TinymistCompilationState? = null,
    val path: String = "",
    val pageCount: Long? = null,
    val wordsCount: TinymistWordCount? = null,
)

data class TinymistWordCount(
    val words: Long = 0,
    val chars: Long = 0,
    val spaces: Long = 0,
    val cjkChars: Long = 0,
)
