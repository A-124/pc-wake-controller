package com.pcwake.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PcDeviceEntity::class, ConnectionProfileEntity::class, CommandHistoryEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun profileDao(): ConnectionProfileDao
    abstract fun historyDao(): HistoryDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "pcwake.db")
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
    }
}
