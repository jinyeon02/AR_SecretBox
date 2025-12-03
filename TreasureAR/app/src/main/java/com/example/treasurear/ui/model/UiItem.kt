package com.example.treasurear.ui.model

sealed class UiItem {

    data class Header(
        val title: String
    ) : UiItem()

    data class SubZone(
        val id: Int,
        val title: String,
        val imageUrl: String?,
        val totalCount: Int,
        val collectedCount: Int
    ) : UiItem()
}
