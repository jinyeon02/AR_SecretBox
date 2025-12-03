package com.example.treasurear.data.initial

import android.content.Context
import com.example.treasurear.data.entity.SubZoneEntity
import com.example.treasurear.data.dao.TreasureDao
import com.example.treasurear.data.entity.TreasureEntity
import com.example.treasurear.data.entity.ZoneEntity
import com.google.gson.Gson

class DataInitializer(
    private val context: Context,
    private val dao: TreasureDao
) {

    suspend fun loadInitialData() {
        val jsonString = context.assets.open("treasure_data.json")
            .bufferedReader().use { it.readText() }

        val data = Gson().fromJson(jsonString, TreasureData::class.java)

        data.zones.forEach { zone ->
            dao.insertZone(
                ZoneEntity(
                    id = zone.id,
                    code = zone.code,
                    name = zone.name
                )
            )

            zone.subZones.forEach { sub ->
                dao.insertSubZone(
                    SubZoneEntity(
                        id = sub.id,
                        zoneId = zone.id,
                        name = sub.name,
                        imageUrl = sub.imageUrl      // 🔥 여기 추가
                    )
                )

                sub.treasures.forEach { t ->
                    dao.insertTreasure(
                        TreasureEntity(
                            id = t.id,
                            subZoneId = sub.id,
                            name = t.name,
                            imageUrl = t.imageUrl,
                            isCollected = t.isCollected
                        )
                    )
                }
            }
        }
    }
}