package com.example.treasurear.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "treasures",
    foreignKeys = [
        ForeignKey(
            entity = SubZoneEntity::class,
            parentColumns = ["id"],
            childColumns = ["subZoneId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("subZoneId")]
)
data class TreasureEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val subZoneId: Int,
    val name: String,
    val imageUrl: String?,   // ← 이것만 추가!
    val isCollected: Boolean
)

