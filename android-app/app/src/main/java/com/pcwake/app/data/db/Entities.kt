package com.pcwake.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "pc_devices")
data class PcDeviceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val macAddress: String,
    val localIp: String?,
    val broadcastAddress: String,
    val port: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastSeenAt: Long? = null,
    val lastWakeAt: Long? = null,
)

@Entity(
    tableName = "connection_profiles",
    foreignKeys = [
        ForeignKey(
            entity = PcDeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("deviceId"),
        Index(value = ["deviceId", "mode"], unique = true),
    ],
)
data class ConnectionProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    /** [com.pcwake.app.core.ConnectionMode.wire] */
    val mode: String,
    val endpoint: String,
    val createdAt: Long,
)

@Entity(
    tableName = "command_history",
    foreignKeys = [
        ForeignKey(
            entity = PcDeviceEntity::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("deviceId"), Index("timestamp")],
)
data class CommandHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: Long,
    /** [com.pcwake.app.core.HistoryType.name] */
    val type: String,
    val timestamp: Long,
    /** [com.pcwake.app.core.CommandResult.name] */
    val result: String,
    val errorMessage: String? = null,
    val detail: String? = null,
)
