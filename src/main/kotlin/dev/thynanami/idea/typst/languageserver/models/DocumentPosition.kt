package dev.thynanami.idea.typst.languageserver.models

import com.google.gson.annotations.SerializedName

data class DocumentPosition(
  @SerializedName("page_no") val pageNo: Int,
  val x: Float,
  val y: Float
)
