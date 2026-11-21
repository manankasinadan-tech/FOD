package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.fodDataStore: DataStore<Preferences> by preferencesDataStore(name = "fod_detector_prefs")

data class UserSettings(
    val rootEnabled: Boolean = false,
    val autoStartMonitoring: Boolean = true,
    val pollingIntervalMs: Long = 1500L
)

class PreferencesManager(private val context: Context) {
    private object Keys {
        val ROOT_ENABLED = booleanPreferencesKey("root_enabled")
        val AUTO_START_MONITORING = booleanPreferencesKey("auto_start_monitoring")
        val POLLING_INTERVAL_MS = longPreferencesKey("polling_interval_ms")
    }

    val settingsFlow: Flow<UserSettings> = context.fodDataStore.data.map { prefs ->
        UserSettings(
            rootEnabled = prefs[Keys.ROOT_ENABLED] ?: false,
            autoStartMonitoring = prefs[Keys.AUTO_START_MONITORING] ?: true,
            pollingIntervalMs = (prefs[Keys.POLLING_INTERVAL_MS] ?: 1500L).coerceIn(1000L, 5000L)
        )
    }

    suspend fun setRootEnabled(enabled: Boolean) {
        context.fodDataStore.edit { it[Keys.ROOT_ENABLED] = enabled }
    }

    suspend fun setAutoStartMonitoring(enabled: Boolean) {
        context.fodDataStore.edit { it[Keys.AUTO_START_MONITORING] = enabled }
    }

    suspend fun setPollingIntervalMs(intervalMs: Long) {
        context.fodDataStore.edit { it[Keys.POLLING_INTERVAL_MS] = intervalMs.coerceIn(1000L, 5000L) }
    }
}
