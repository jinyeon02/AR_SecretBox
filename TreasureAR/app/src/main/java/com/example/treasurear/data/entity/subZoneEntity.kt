package com.example.treasurear.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Index
@Entity(
    tableName = "subzones",
    foreignKeys = [
        ForeignKey(
            entity = ZoneEntity::class,
            parentColumns = ["id"],
            childColumns = ["zoneId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("zoneId")]
)
data class SubZoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val zoneId: Int,
    val name: String,
    val imageUrl: String? = null
)
