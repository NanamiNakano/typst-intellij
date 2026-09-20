package dev.thynanami.idea.typst.lsp

import com.google.gson.annotations.SerializedName

data class DocumentOutline(val items: List<OutlineItem> = emptyList())

data class OutlineItem(
    val title: String,
    val span: String? = null,
    val position: DocumentPosition? = null,
    val children: List<OutlineItem> = emptyList(),
)

data class DocumentPosition(
    @SerializedName("page_no") val pageNo: Int,
    val x: Double,
    val y: Double,
)

data class PreviewDisposed(val taskId: String)

data class PreviewScrollRequest(
    val event: String,
    val span: String? = null,
    val position: DocumentPosition? = null,
)

fun OutlineItem.scrollRequests(): List<PreviewScrollRequest> = buildList {
    span?.takeIf(String::isNotBlank)?.let {
        add(PreviewScrollRequest(event = "sourceScrollBySpan", span = it))
    }
    position?.takeIf { it.pageNo > 0 && it.x.isFinite() && it.y.isFinite() }?.let {
        add(PreviewScrollRequest(event = "panelScrollByPosition", position = it))
    }
}
