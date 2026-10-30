package com.pcwake.app.data

import androidx.room.withTransaction
import com.pcwake.app.core.ConnectionMode
import com.pcwake.app.core.Device
import com.pcwake.app.core.DeviceConnection
import com.pcwake.app.core.DeviceWithConnections
import com.pcwake.app.core.MacAddress
import com.pcwake.app.data.db.AppDatabase
import com.pcwake.app.data.db.ConnectionProfileEntity
import com.pcwake.app.data.db.PcDeviceEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class DeviceRepository(private val db: AppDatabase) {
    private val deviceDao = db.deviceDao()
    private val profileDao = db.profileDao()

    val devices: Flow<List<DeviceWithConnections>> =
        combine(deviceDao.observeAll(), profileDao.observeAll()) { devices, profiles ->
            val byDevice = profiles.groupBy { it.deviceId }
            devices.map { device ->
                DeviceWithConnections(
                    device = device.toModel(),
                    connections = (byDevice[device.id].orEmpty()).mapNotNull { profile ->
                        ConnectionMode.fromWire(profile.mode)?.let { mode ->
                            DeviceConnection(mode, profile.endpoint)
                        }
                    },
                )
            }
        }

    fun observeDevice(id: Long): Flow<DeviceWithConnections?> =
        combine(deviceDao.observeById(id), profileDao.observeForDevice(id)) { device, profiles ->
            device?.let {
                DeviceWithConnections(
                    device = it.toModel(),
                    connections = profiles.mapNotNull { profile ->
                        ConnectionMode.fromWire(profile.mode)?.let { mode ->
                            DeviceConnection(mode, profile.endpoint)
                        }
                    },
                )
            }
        }

    suspend fun get(id: Long): DeviceWithConnections? {
        val device = deviceDao.getById(id) ?: return null
        val profiles = profileDao.getForDevice(id)
        return DeviceWithConnections(
            device = device.toModel(),
            connections = profiles.mapNotNull { p ->
                ConnectionMode.fromWire(p.mode)?.let { DeviceConnection(it, p.endpoint) }
            },
        )
    }

    suspend fun getAllDevices(): List<PcDeviceEntity> = deviceDao.getAll()

    suspend fun getAllWithConnections(): List<DeviceWithConnections> =
        deviceDao.getAll().map { device ->
            DeviceWithConnections(
                device = device.toModel(),
                connections = profileDao.getForDevice(device.id).mapNotNull { p ->
                    ConnectionMode.fromWire(p.mode)?.let { DeviceConnection(it, p.endpoint) }
                },
            )
        }

    data class DeviceInput(
        val name: String,
        val macAddress: String,
        val localIp: String?,
        val broadcastAddress: String,
        val port: Int,
        val agentEndpoint: String?,
        val bridgeEndpoint: String?,
    )

    suspend fun save(existing: Device?, input: DeviceInput): Long = db.withTransaction {
        val now = System.currentTimeMillis()
        val entity = PcDeviceEntity(
            id = existing?.id ?: 0,
            name = input.name.trim(),
            macAddress = input.macAddress,
            localIp = input.localIp?.trim()?.takeIf(String::isNotEmpty),
            broadcastAddress = input.broadcastAddress.trim(),
            port = input.port,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            lastSeenAt = existing?.lastSeenAt,
            lastWakeAt = existing?.lastWakeAt,
        )
        val id = if (existing == null) {
            deviceDao.insert(entity)
        } else {
            deviceDao.update(entity)
            existing.id
        }
        profileDao.deleteForDevice(id)
        input.agentEndpoint?.trim()?.takeIf(String::isNotEmpty)?.let { endpoint ->
            profileDao.upsert(
                ConnectionProfileEntity(
                    deviceId = id,
                    mode = ConnectionMode.AGENT.wire,
                    endpoint = endpoint,
                    createdAt = now,
                ),
            )
        }
        input.bridgeEndpoint?.trim()?.takeIf(String::isNotEmpty)?.let { endpoint ->
            profileDao.upsert(
                ConnectionProfileEntity(
                    deviceId = id,
                    mode = ConnectionMode.BRIDGE.wire,
                    endpoint = endpoint,
                    createdAt = now,
                ),
            )
        }
        id
    }

    suspend fun duplicate(id: Long): Long? = db.withTransaction {
        val source = deviceDao.getById(id) ?: return@withTransaction null
        val profiles = profileDao.getForDevice(id)
        val now = System.currentTimeMillis()
        val newId = deviceDao.insert(
            source.copy(
                id = 0,
                name = source.name + " copy",
                createdAt = now,
                updatedAt = now,
                lastSeenAt = null,
                lastWakeAt = null,
            ),
        )
        profiles.forEach {
            profileDao.upsert(it.copy(id = 0, deviceId = newId, createdAt = now))
        }
        newId
    }

    suspend fun delete(id: Long) {
        deviceDao.deleteById(id)
    }

    suspend fun markSeen(id: Long) = deviceDao.markSeen(id, System.currentTimeMillis())
    suspend fun markWake(id: Long) = deviceDao.markWake(id, System.currentTimeMillis())

    suspend fun importDevices(inputs: List<DeviceInput>) {
        inputs.forEach { input ->
            val mac = MacAddress.parseOrNull(input.macAddress)?.canonical ?: return@forEach
            val existing = deviceDao.findByMac(mac)
            save(
                existing = existing?.toModel(),
                input = input.copy(macAddress = mac),
            )
        }
    }
}

private fun PcDeviceEntity.toModel(): Device = Device(
    id = id,
    name = name,
    macAddress = macAddress,
    localIp = localIp,
    broadcastAddress = broadcastAddress,
    port = port,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastSeenAt = lastSeenAt,
    lastWakeAt = lastWakeAt,
)
