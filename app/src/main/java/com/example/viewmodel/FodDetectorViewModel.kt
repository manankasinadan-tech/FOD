package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.PreferencesManager
import com.example.data.UserSettings
import com.example.engine.FodEngineController
import com.example.engine.FodEngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MainTab(val route: String) {
    COCKPIT("cockpit"),
    MECHANISM("mechanism"),
    DISCOVERIES("discoveries"),
    SETTINGS("settings")
}

class FodDetectorViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsManager = PreferencesManager(application)

    val engineState: StateFlow<FodEngineState> = FodEngineController.state

    val userSettings: StateFlow<UserSettings> = prefsManager.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = UserSettings()
    )

    private val _currentTab = MutableStateFlow(MainTab.COCKPIT)
    val currentTab: StateFlow<MainTab> = _currentTab.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    init {
        FodEngineController.initializeOnLaunch(getApplication())
    }

    fun selectTab(tab: MainTab) {
        _currentTab.value = tab
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun onAppResumed() {
        FodEngineController.onAppResumed(getApplication())
    }

    fun toggleMonitoring() {
        val ctx = getApplication<Application>()
        val isRunning = engineState.value.permissions.isForegroundServiceActive ||
            engineState.value.phase.name in listOf("MONITORING_PASSIVE", "INVESTIGATING")
        if (isRunning) {
            FodEngineController.stopMonitoringLoop(ctx)
        } else {
            FodEngineController.startMonitoring(ctx)
        }
    }

    fun startNewSession() {
        viewModelScope.launch {
            FodEngineController.restartNewSession(getApplication())
            _toastMessage.value = "Nouvelle session d'analyse FOD initialisée."
        }
    }

    fun finalizeReconstruction() {
        viewModelScope.launch {
            FodEngineController.finalizeAndReconstruct(getApplication())
            _toastMessage.value = "Cartographie et rapport exportés."
        }
    }

    fun setRootEnabled(enabled: Boolean) {
        viewModelScope.launch {
            FodEngineController.setRootEnabledAndRefresh(getApplication(), enabled)
        }
    }

    fun runRootTest() {
        viewModelScope.launch {
            FodEngineController.runManualRootTest(getApplication())
        }
    }

    fun setAutoStartMonitoring(enabled: Boolean) {
        viewModelScope.launch {
            prefsManager.setAutoStartMonitoring(enabled)
        }
    }

    fun setPollingInterval(intervalMs: Long) {
        viewModelScope.launch {
            prefsManager.setPollingIntervalMs(intervalMs)
        }
    }

    fun syncToSdcardFod() {
        viewModelScope.launch {
            _toastMessage.value = FodEngineController.syncPendingExportToSdcard(getApplication())
        }
    }

    fun buildFingerprintEnrollIntent(): Pair<Intent, String> {
        val ctx = getApplication<Application>()
        val pm = ctx.packageManager

        val candidates = listOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_BIOMETRIC_ENROLL)
            } else {
                Intent("android.settings.FINGERPRINT_ENROLL")
            },
            Intent("android.settings.FINGERPRINT_ENROLL"),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )

        for (candidate in candidates) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (candidate.resolveActivity(pm) != null) {
                return candidate to (candidate.action ?: Settings.ACTION_SETTINGS)
            }
        }
        val fallback = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return fallback to Settings.ACTION_SETTINGS
    }

    fun notifySettingsEnrollLaunched(actionName: String) {
        FodEngineController.onSettingsEnrollLaunched(getApplication(), actionName)
    }
}
