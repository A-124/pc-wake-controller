package com.pcwake.app

import android.app.Application
import android.content.Context
import com.pcwake.app.data.BackupManager
import com.pcwake.app.data.DeviceRepository
import com.pcwake.app.data.HistoryRepository
import com.pcwake.app.data.SecretVault
import com.pcwake.app.data.SettingsRepository
import com.pcwake.app.data.db.AppDatabase as RoomDatabase
import com.pcwake.app.domain.DeviceMonitor
import com.pcwake.app.domain.PowerCoordinator
import com.pcwake.app.net.AgentApi
import com.pcwake.app.net.BridgeApi
import com.pcwake.app.net.DiscoveryClient
import com.pcwake.app.net.NetworkInfoProvider
import com.pcwake.app.net.PinnedHttpClientFactory
import com.pcwake.app.net.WolSender

class AppContainer(context: Context) {
    val database: RoomDatabase = RoomDatabase.build(context)
    val deviceRepository = DeviceRepository(database)
    val historyRepository = HistoryRepository(database)
    val settingsRepository = SettingsRepository(context)
    val secretVault = SecretVault(context)
    val httpFactory = PinnedHttpClientFactory()
    val agentApi = AgentApi(httpFactory)
    val bridgeApi = BridgeApi(httpFactory)
    val wolSender = WolSender()
    val discoveryClient = DiscoveryClient()
    val networkInfo = NetworkInfoProvider(context)
    val monitor = DeviceMonitor(agentApi, bridgeApi, secretVault)
    val powerCoordinator = PowerCoordinator(
        deviceRepository = deviceRepository,
        historyRepository = historyRepository,
        vault = secretVault,
        wolSender = wolSender,
        agentApi = agentApi,
        bridgeApi = bridgeApi,
        settingsRepository = settingsRepository,
    )
    val backupManager = BackupManager(context, deviceRepository, historyRepository)
}

class PcWakeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
