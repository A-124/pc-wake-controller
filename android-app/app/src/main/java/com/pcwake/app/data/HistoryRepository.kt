package com.pcwake.app.data

import com.pcwake.app.core.CommandRecord
import com.pcwake.app.core.CommandResult
import com.pcwake.app.core.HistoryType
import com.pcwake.app.data.db.AppDatabase
import com.pcwake.app.data.db.CommandHistoryEntity
import com.pcwake.app.data.db.CommandWithDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class HistoryRepository(private val db: AppDatabase) {
    private val dao = db.historyDao()

    fun observeRecent(limit: Int = 200): Flow<List<CommandRecord>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toModel() } }

    fun observeForDevice(deviceId: Long, limit: Int = 100): Flow<List<CommandRecord>> =
        dao.observeForDevice(deviceId, limit).map { rows -> rows.map { it.toModel() } }

    suspend fun log(
        deviceId: Long,
        type: HistoryType,
        result: CommandResult,
        errorMessage: String? = null,
        detail: String? = null,
    ) {
        dao.insert(
            CommandHistoryEntity(
                deviceId = deviceId,
                type = type.name,
                timestamp = System.currentTimeMillis(),
                result = result.name,
                errorMessage = errorMessage,
                detail = detail,
            ),
        )
    }

    suspend fun latestForDevice(deviceId: Long): CommandRecord? =
        dao.latestForDevice(deviceId)?.let {
            CommandRecord(
                id = it.id,
                deviceId = it.deviceId,
                deviceName = "",
                type = runCatching { HistoryType.valueOf(it.type) }.getOrDefault(HistoryType.TEST),
                timestamp = it.timestamp,
                result = runCatching { CommandResult.valueOf(it.result) }.getOrDefault(CommandResult.FAILED),
                errorMessage = it.errorMessage,
                detail = it.detail,
            )
        }

    suspend fun clearAll() = dao.clearAll()

    suspend fun exportCsv(): String {
        val rows = dao.getAll()
        return buildString {
            append("timestamp,device,type,result,error,detail\n")
            rows.forEach { row ->
                append(csvDate(row.history.timestamp)).append(',')
                append(csv(row.deviceName ?: "deleted device")).append(',')
                append(csv(row.history.type)).append(',')
                append(csv(row.history.result)).append(',')
                append(csv(row.history.errorMessage ?: "")).append(',')
                append(csv(row.history.detail ?: "")).append('\n')
            }
        }
    }

    private fun csv(value: String): String {
        val clean = value.replace("\r", " ").replace("\n", " ")
        return "\"" + clean.replace("\"", "\"\"") + "\""
    }

    private fun csvDate(timestamp: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date(timestamp))
}

private fun CommandWithDevice.toModel(): CommandRecord = CommandRecord(
    id = history.id,
    deviceId = history.deviceId,
    deviceName = deviceName ?: "Deleted device",
    type = runCatching { HistoryType.valueOf(history.type) }.getOrDefault(HistoryType.TEST),
    timestamp = history.timestamp,
    result = runCatching { CommandResult.valueOf(history.result) }.getOrDefault(CommandResult.FAILED),
    errorMessage = history.errorMessage,
    detail = history.detail,
)
