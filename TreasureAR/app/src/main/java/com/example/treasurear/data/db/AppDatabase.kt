package com.example.treasurear.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.treasurear.data.entity.SubZoneEntity
import com.example.treasurear.data.dao.TreasureDao
import com.example.treasurear.data.entity.TreasureEntity
import com.example.treasurear.data.entity.ZoneEntity

@Database(
    entities = [ZoneEntity::class, SubZoneEntity::class, TreasureEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun treasureDao(): TreasureDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private const val DB_NAME = "treasure.db"   // 🔥 여기 이름 고정

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                ).build().also { INSTANCE = it }
            }
        }
    }
}