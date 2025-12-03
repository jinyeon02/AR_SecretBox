package com.example.treasurear.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "zones")
data class ZoneEntity(
    @PrimaryKey val id: Int,
    val code: String,   // W15
    val name: String    // 건물 이름
)
