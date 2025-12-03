package com.example.treasurear.data.initial

data class TreasureData(
    val zones: List<ZoneJson>
)

data class ZoneJson(
    val id: Int,
    val code: String,
    val name: String,
    val subZones: List<SubZoneJson>
)

data class SubZoneJson(
    val id: Int,
    val name: String,
    val imageUrl: String,                 // 🔥 반드시 추가
    val treasures: List<TreasureJson>
)

data class TreasureJson(
    val id: Int,
    val name: String,
    val description: String,
    val imageUrl: String,
    val isCollected: Boolean = false      // 있으면 좋음(옵션)
)