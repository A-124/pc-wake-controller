package com.pcwake.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pcwake.app.core.ThemeMode
import com.pcwake.app.core.WakeRoutePref
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val onboarded: Boolean = false,
    val defaultRoute: WakeRoutePref = WakeRoutePref.ASK,
    val defaultPort: Int = 9,
    val defaultBroadcast: String = "",
    val retries: Int = 3,
    val retryIntervalMs: Int = 500,
    val timeoutMs: Int = 8_000,
    val refreshSeconds: Int = 15,
    val wakeInterface: String = "",
)

class SettingsRepository(context: Context) {
    private val dataStore = context.applicationContext.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                theme = runCatching { ThemeMode.valueOf(prefs[KEY_THEME] ?: ThemeMode.DARK.name) }
                    .getOrDefault(ThemeMode.DARK),
                onboarded = prefs[KEY_ONBOARDED] ?: false,
                defaultRoute = runCatching { WakeRoutePref.valueOf(prefs[KEY_ROUTE] ?: WakeRoutePref.ASK.name) }
                    .getOrDefault(WakeRoutePref.ASK),
                defaultPort = (prefs[KEY_PORT] ?: 9).coerceIn(1, 65535),
                defaultBroadcast = prefs[KEY_BROADCAST] ?: "",
                retries = (prefs[KEY_RETRIES] ?: 3).coerceIn(1, 20),
                retryIntervalMs = (prefs[KEY_INTERVAL] ?: 500).coerceIn(50, 5_000),
                timeoutMs = (prefs[KEY_TIMEOUT] ?: 8_000).coerceIn(1_000, 60_000),
                refreshSeconds = (prefs[KEY_REFRESH] ?: 15).coerceIn(5, 300),
                wakeInterface = prefs[KEY_INTERFACE] ?: "",
            )
        }

    suspend fun setTheme(mode: ThemeMode) = edit { it[KEY_THEME] = mode.name }
    suspend fun setOnboarded(value: Boolean) = edit { it[KEY_ONBOARDED] = value }
    suspend fun setDefaultRoute(route: WakeRoutePref) = edit { it[KEY_ROUTE] = route.name }
    suspend fun setDefaultPort(port: Int) = edit { it[KEY_PORT] = port.coerceIn(1, 65535) }
    suspend fun setDefaultBroadcast(value: String) = edit { it[KEY_BROADCAST] = value.trim() }
    suspend fun setRetries(value: Int) = edit { it[KEY_RETRIES] = value.coerceIn(1, 20) }
    suspend fun setRetryInterval(value: Int) = edit { it[KEY_INTERVAL] = value.coerceIn(50, 5_000) }
    suspend fun setTimeout(value: Int) = edit { it[KEY_TIMEOUT] = value.coerceIn(1_000, 60_000) }
    suspend fun setRefreshSeconds(value: Int) = edit { it[KEY_REFRESH] = value.coerceIn(5, 300) }
    suspend fun setWakeInterface(value: String) = edit { it[KEY_INTERFACE] = value }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
        val KEY_ROUTE = stringPreferencesKey("default_route")
        val KEY_PORT = intPreferencesKey("default_udp_port")
        val KEY_BROADCAST = stringPreferencesKey("default_broadcast")
        val KEY_RETRIES = intPreferencesKey("packet_retries")
        val KEY_INTERVAL = intPreferencesKey("retry_interval_ms")
        val KEY_TIMEOUT = intPreferencesKey("connection_timeout_ms")
        val KEY_REFRESH = intPreferencesKey("refresh_seconds")
        val KEY_INTERFACE = stringPreferencesKey("wake_interface")
    }
}
