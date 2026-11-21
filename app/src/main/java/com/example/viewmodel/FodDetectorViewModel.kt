package com.example.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.FodStorageExporter
import com.example.data.PreferencesManager
import com.example.data.UserSettings
import com.example.engine.FodComparatorEngine
import com.example.engine.FodEngineController
import com.example.engine.FodEngineState
import com.example.model.FodComparisonReport
import com.example.model.ImportedFodSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

enum class MainTab(val route: String) {
    COCKPIT("cockpit"),
    MECHANISM("mechanism"),
    DISCOVERIES("discoveries"),
    COMPARATOR("comparator"),
    SETTINGS("settings")
}

data class ComparatorUiState(
    val sessionA: ImportedFodSession? = null,
    val sessionB: ImportedFodSession? = null,
    val report: FodComparisonReport? = null,
    val localZipFiles: List<File> = emptyList(),
    val isImporting: Boolean = false
)

class FodDetectorViewModel(application: Application) : AndroidViewModel(application) {

    private val prefsManager = PreferencesManager(application)
    private val exporter = FodStorageExporter(application)

    val engineState: StateFlow<FodEngineState> = FodEngineController.state

    val userSettings: StateFlow<UserSettings> = prefsManager.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = UserSettings()
    )

    private val _currentTab = MutableStateFlow(MainTab.COCKPIT)
    val currentTab: StateFlow<MainTab> = _currentTab.asStateFlow()

    private val _comparatorState = MutableStateFlow(ComparatorUiState())
    val comparatorState: StateFlow<ComparatorUiState> = _comparatorState.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    init {
        FodEngineController.initializeOnLaunch(getApplication())
        refreshLocalZips()
    }

    fun selectTab(tab: MainTab) {
        _currentTab.value = tab
        if (tab == MainTab.COMPARATOR || tab == MainTab.DISCOVERIES) {
            refreshLocalZips()
        }
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun onAppResumed() {
        FodEngineController.onAppResumed(getApplication())
        refreshLocalZips()
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
            refreshLocalZips()
            _toastMessage.value = "Nouvelle session d'analyse FOD initialisée."
        }
    }

    fun finalizeReconstruction() {
        viewModelScope.launch {
            FodEngineController.finalizeAndReconstruct(getApplication())
            refreshLocalZips()
            _toastMessage.value = "3 JSON + evidence/all_evidence.txt + ZIP générés."
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
            refreshLocalZips()
        }
    }

    fun refreshLocalZips() {
        viewModelScope.launch {
            val files = exporter.listLocalZipFiles()
            _comparatorState.value = _comparatorState.value.copy(localZipFiles = files)
        }
    }

    fun exportCurrentZipToUri(uri: Uri) {
        viewModelScope.launch {
            val sessionId = engineState.value.exportState.sessionId
            if (sessionId.isBlank()) {
                _toastMessage.value = "Aucune session active à exporter en ZIP."
                return@launch
            }
            val resolver = getApplication<Application>().contentResolver
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                _toastMessage.value = "Impossible d'ouvrir la destination ZIP sélectionnée."
                return@launch
            }
            val res = exporter.exportZipToStream(sessionId, out)
            _toastMessage.value = res.fold(
                onSuccess = { bytes -> "Archive ZIP exportée (${bytes / 1024} Ko)." },
                onFailure = { err -> err.localizedMessage ?: "Erreur lors de l'export ZIP." }
            )
        }
    }

    fun importZipForSlot(slotA: Boolean, uri: Uri) {
        viewModelScope.launch {
            _comparatorState.value = _comparatorState.value.copy(isImporting = true)
            val resolver = getApplication<Application>().contentResolver
            val name = resolveDisplayName(uri) ?: "archive_importee.zip"
            val stream = resolver.openInputStream(uri)
            if (stream == null) {
                _comparatorState.value = _comparatorState.value.copy(isImporting = false)
                _toastMessage.value = "Impossible de lire le fichier ZIP sélectionné."
                return@launch
            }
            val res = stream.use { exporter.importSessionFromZip(it, name) }
            applyImportedSlotResult(slotA, res)
        }
    }

    fun importLocalZipForSlot(slotA: Boolean, file: File) {
        viewModelScope.launch {
            _comparatorState.value = _comparatorState.value.copy(isImporting = true)
            val res = exporter.importSessionFromLocalZip(file)
            applyImportedSlotResult(slotA, res)
        }
    }

    fun useLiveSessionForSlot(slotA: Boolean) {
        val st = engineState.value
        val sessionId = st.exportState.sessionId.ifBlank { "live_session" }
        val liveImported = exporter.buildImportedSessionFromLive(
            sessionId = sessionId,
            profile = st.deviceProfile,
            permissions = st.permissions,
            components = st.components,
            modifications = st.modifications,
            flattenedHals = st.flattenedHals,
            reconstruction = st.reconstruction,
            artifacts = st.rawArtifacts,
            eventsCount = st.events.size
        )
        applyImportedSlotResult(slotA, Result.success(liveImported))
    }

    fun clearComparator() {
        _comparatorState.value = _comparatorState.value.copy(
            sessionA = null,
            sessionB = null,
            report = null
        )
    }

    private fun applyImportedSlotResult(slotA: Boolean, res: Result<ImportedFodSession>) {
        res.fold(
            onSuccess = { imported ->
                val cur = _comparatorState.value
                val newA = if (slotA) imported else cur.sessionA
                val newB = if (!slotA) imported else cur.sessionB
                val report = if (newA != null && newB != null) {
                    FodComparatorEngine.compare(newA, newB)
                } else {
                    null
                }
                _comparatorState.value = cur.copy(
                    sessionA = newA,
                    sessionB = newB,
                    report = report,
                    isImporting = false
                )
                _toastMessage.value = if (slotA) {
                    "Archive A (Référence) importée : ${imported.manufacturerModel}"
                } else {
                    "Archive B (Cible) importée : ${imported.manufacturerModel}"
                }
            },
            onFailure = { err ->
                _comparatorState.value = _comparatorState.value.copy(isImporting = false)
                _toastMessage.value = err.localizedMessage ?: "Échec d'importation de l'archive ZIP."
            }
        )
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return runCatching {
            getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        }.getOrNull()
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
