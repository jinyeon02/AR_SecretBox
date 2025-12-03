package com.example.treasurear.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.treasurear.data.entity.ZoneEntity
import com.example.treasurear.data.entity.SubZoneEntity
import com.example.treasurear.data.entity.TreasureEntity

@Dao
interface TreasureDao {

    // --- Zone ---
    @Query("SELECT * FROM zones")
    suspend fun getZones(): List<ZoneEntity>

    // --- SubZone ---
    @Query("SELECT * FROM subzones WHERE zoneId = :zoneId")
    suspend fun getSubZones(zoneId: Int): List<SubZoneEntity>

    // --- Treasure ---
    @Query("SELECT * FROM treasures WHERE subZoneId = :subZoneId")
    suspend fun getTreasures(subZoneId: Int): List<TreasureEntity>

    @Query("SELECT COUNT(*) FROM treasures WHERE subZoneId = :subZoneId")
    suspend fun getTreasureCount(subZoneId: Int): Int

    @Query("SELECT COUNT(*) FROM treasures WHERE subZoneId = :subZoneId AND isCollected = 1")
    suspend fun getCollectedCountBySubZone(subZoneId: Int): Int

    // --- 전체 도감 수 ---
    @Query("SELECT COUNT(*) FROM treasures")
    suspend fun getTotalTreasureCount(): Int

    @Query("SELECT COUNT(*) FROM treasures WHERE isCollected = 1")
    suspend fun getTotalCollectedCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertZone(zone: ZoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubZone(subZone: SubZoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTreasure(treasure: TreasureEntity)

    // ⭐ 아직 수집 안 한 것 중 하나 가져오기 (테스트용)
    @Query("SELECT * FROM treasures WHERE isCollected = 0 LIMIT 1")
    suspend fun getFirstUncollectedTreasure(): TreasureEntity?

    // ⭐ 특정 보물을 수집 처리
    @Query("UPDATE treasures SET isCollected = 1 WHERE id = :treasureId")
    suspend fun collectTreasure(treasureId: Int)

    // ⭐ 특정 SubZone 안에서 미수집 보물 1개 랜덤 선택
    @Query("""
        SELECT * FROM treasures
        WHERE subZoneId = :subZoneId AND isCollected = 0
        ORDER BY RANDOM()
        LIMIT 1
    """)
    suspend fun getRandomUncollectedBySubZone(subZoneId: Int): TreasureEntity?

    // [★추가됨] 전체 보물 중 미수집 1개 랜덤 선택 (AR 랜덤 스폰용)
    @Query("SELECT * FROM treasures WHERE isCollected = 0 ORDER BY RANDOM() LIMIT 1")
    suspend fun getRandomUncollectedTreasure(): TreasureEntity?
}