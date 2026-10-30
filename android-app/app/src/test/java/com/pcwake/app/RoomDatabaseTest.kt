package com.pcwake.app

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pcwake.app.data.db.AppDatabase
import com.pcwake.app.data.db.CommandHistoryEntity
import com.pcwake.app.data.db.ConnectionProfileEntity
import com.pcwake.app.data.db.PcDeviceEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `device profiles and history cascade on delete`() = runBlocking {
        val now = 1_720_000_000_000L
        val id = db.deviceDao().insert(
            PcDeviceEntity(
                name = "Gaming PC",
                macAddress = "AA:BB:CC:DD:EE:FF",
                localIp = "192.168.1.50",
                broadcastAddress = "192.168.1.255",
                port = 9,
                createdAt = now,
                updatedAt = now,
            ),
        )
        db.profileDao().upsert(
            ConnectionProfileEntity(
                deviceId = id,
                mode = "agent",
                endpoint = "https://192.168.1.50:47101",
                createdAt = now,
            ),
        )
        db.profileDao().upsert(
            ConnectionProfileEntity(
                deviceId = id,
                mode = "bridge",
                endpoint = "https://bridge.example.net:8443",
                createdAt = now,
            ),
        )
        assertEquals(2, db.profileDao().getForDevice(id).size)

        db.historyDao().insert(
            CommandHistoryEntity(
                deviceId = id,
                type = "LOCAL_WAKE",
                timestamp = now,
                result = "SENT",
                detail = "3 magic packets",
            ),
        )
        val recent = db.historyDao().observeRecent(10).first()
        assertEquals(1, recent.size)
        assertEquals("Gaming PC", recent.first().deviceName)
        assertEquals("SENT", recent.first().history.result)

        db.deviceDao().markSeen(id, now + 5_000)
        db.deviceDao().markWake(id, now + 6_000)
        val updated = db.deviceDao().getById(id)!!
        assertEquals(now + 5_000, updated.lastSeenAt)
        assertEquals(now + 6_000, updated.lastWakeAt)

        db.deviceDao().deleteById(id)
        assertNull(db.deviceDao().getById(id))
        assertTrue(db.profileDao().getForDevice(id).isEmpty())
        assertTrue(db.historyDao().observeRecent(10).first().isEmpty())
    }

    @Test
    fun `only one profile per mode is kept`() = runBlocking {
        val id = db.deviceDao().insert(
            PcDeviceEntity(
                name = "Office PC",
                macAddress = "11:22:33:44:55:66",
                localIp = null,
                broadcastAddress = "10.0.0.255",
                port = 7,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        db.profileDao().upsert(
            ConnectionProfileEntity(deviceId = id, mode = "agent", endpoint = "https://a:47101", createdAt = 1),
        )
        db.profileDao().upsert(
            ConnectionProfileEntity(deviceId = id, mode = "agent", endpoint = "https://b:47101", createdAt = 2),
        )
        val profiles = db.profileDao().getForDevice(id)
        assertEquals(1, profiles.size)
        assertEquals("https://b:47101", profiles.first().endpoint)
    }
}
