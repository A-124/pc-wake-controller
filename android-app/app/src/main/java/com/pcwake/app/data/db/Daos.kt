package com.pcwake.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Query("SELECT * FROM pc_devices ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PcDeviceEntity>>

    @Query("SELECT * FROM pc_devices WHERE id = :id")
    fun observeById(id: Long): Flow<PcDeviceEntity?>

    @Query("SELECT * FROM pc_devices WHERE id = :id")
    suspend fun getById(id: Long): PcDeviceEntity?

    @Query("SELECT * FROM pc_devices WHERE macAddress = :mac LIMIT 1")
    suspend fun findByMac(mac: String): PcDeviceEntity?

    @Query("SELECT * FROM pc_devices ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<PcDeviceEntity>

    @Insert
    suspend fun insert(device: PcDeviceEntity): Long

    @Update
    suspend fun update(device: PcDeviceEntity)

    @Query("DELETE FROM pc_devices WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE pc_devices SET lastSeenAt = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun markSeen(id: Long, timestamp: Long)

    @Query("UPDATE pc_devices SET lastWakeAt = :timestamp, updatedAt = :timestamp WHERE id = :id")
    suspend fun markWake(id: Long, timestamp: Long)
}

@Dao
interface ConnectionProfileDao {
    @Query("SELECT * FROM connection_profiles ORDER BY deviceId, mode")
    fun observeAll(): Flow<List<ConnectionProfileEntity>>

    @Query("SELECT * FROM connection_profiles WHERE deviceId = :deviceId")
    fun observeForDevice(deviceId: Long): Flow<List<ConnectionProfileEntity>>

    @Query("SELECT * FROM connection_profiles WHERE deviceId = :deviceId")
    suspend fun getForDevice(deviceId: Long): List<ConnectionProfileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: ConnectionProfileEntity)

    @Query("DELETE FROM connection_profiles WHERE deviceId = :deviceId")
    suspend fun deleteForDevice(deviceId: Long)

    @Query("DELETE FROM connection_profiles WHERE deviceId = :deviceId AND mode = :mode")
    suspend fun deleteForDeviceMode(deviceId: Long, mode: String)
}

data class CommandWithDevice(
    @Embedded val history: CommandHistoryEntity,
    val deviceName: String?,
)

@Dao
interface HistoryDao {
    @Query(
        """
        SELECT h.*, d.name AS deviceName
        FROM command_history h
        LEFT JOIN pc_devices d ON d.id = h.deviceId
        ORDER BY h.timestamp DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<CommandWithDevice>>

    @Query(
        """
        SELECT h.*, d.name AS deviceName
        FROM command_history h
        LEFT JOIN pc_devices d ON d.id = h.deviceId
        WHERE h.deviceId = :deviceId
        ORDER BY h.timestamp DESC
        LIMIT :limit
        """,
    )
    fun observeForDevice(deviceId: Long, limit: Int): Flow<List<CommandWithDevice>>

    @Query(
        """
        SELECT h.*, d.name AS deviceName
        FROM command_history h
        LEFT JOIN pc_devices d ON d.id = h.deviceId
        ORDER BY h.timestamp DESC
        """,
    )
    suspend fun getAll(): List<CommandWithDevice>

    @Insert
    suspend fun insert(history: CommandHistoryEntity): Long

    @Query("DELETE FROM command_history")
    suspend fun clearAll()

    @Query("SELECT * FROM command_history WHERE deviceId = :deviceId ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestForDevice(deviceId: Long): CommandHistoryEntity?
}
