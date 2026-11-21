package com.example.engine

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.FodStorageExporter
import com.example.data.PreferencesManager
import com.example.model.BaselineSnapshot
import com.example.model.CapabilityLimitation
import com.example.model.ComponentDependency
import com.example.model.DetectedModification
import com.example.model.DeviceProfile
import com.example.model.DiscoveredComponent
import com.example.model.EnginePhase
import com.example.model.EvidenceLevel
import com.example.model.ExportSessionState
import com.example.model.FodEvent
import com.example.model.MechanismReconstruction
import com.example.model.PermissionsState
import com.example.model.RootDiagnosticResult
import com.example.service.FodMonitoringService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class FodEngineState(
    val phase: EnginePhase = EnginePhase.INITIALIZING,
    val permissions: PermissionsState = PermissionsState(),
    val deviceProfile: DeviceProfile = DeviceProfile(),
    val baseline: BaselineSnapshot? = null,
    val events: List<FodEvent> = emptyList(),
    val components: List<DiscoveredComponent> = emptyList(),
    val dependencies: List<ComponentDependency> = emptyList(),
    val modifications: List<DetectedModification> = emptyList(),
    val reconstruction: MechanismReconstruction? = null,
    val exportState: ExportSessionState = ExportSessionState(),
    val rawArtifacts: RawCollectorArtifacts = RawCollectorArtifacts(),
    val isBusyAction: Boolean = false,
    val statusBanner: String = "Initialisation automatique du moteur FOD..."
)

object FodEngineController {

    private const val TAG = "FodEngineController"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var monitorJob: Job? = null
    private var initialized = false

    private val _state = MutableStateFlow(FodEngineState())
    val state: StateFlow<FodEngineState> = _state.asStateFlow()

    private val seenLogHashes = mutableSetOf<Int>()
    private var lastBioCode: Int = -1
    private var lastBrightness: Int = -1
    private var lastRefreshHz: Float = 0f
    private var lastEnrolledCount: Int? = null
    private var lastSysfsSnapshot: Map<String, String> = emptyMap()
    private var enrollmentSucceededFlag = false
    private var firstFailureLine: String? = null
    private var currentSessionId: String = ""
    private var deepWaveTriggered = false
    private var settingsOpenedDuringSession = false

    fun initializeOnLaunch(context: Context) {
        val appCtx = context.applicationContext
        if (initialized) {
            refreshPermissionsAndExportStatus(appCtx)
            return
        }
        initialized = true

        scope.launch {
            runFullInitialization(appCtx)
        }
    }

    suspend fun restartNewSession(context: Context) {
        val appCtx = context.applicationContext
        stopMonitoringLoop(appCtx)
        seenLogHashes.clear()
        enrollmentSucceededFlag = false
        firstFailureLine = null
        deepWaveTriggered = false
        settingsOpenedDuringSession = false
        runFullInitialization(appCtx)
    }

    private suspend fun runFullInitialization(appCtx: Context) {
        _state.value = _state.value.copy(
            phase = EnginePhase.INITIALIZING,
            isBusyAction = true,
            statusBanner = "Reconnaissance de l'appareil et des capacités..."
        )

        val prefs = PreferencesManager(appCtx).settingsFlow.first()
        val exporter = FodStorageExporter(appCtx)
        currentSessionId = exporter.createSessionId()

        // 1. Vérification ROOT uniquement si activé par l'utilisateur (sans bloquer en cas de refus)
        val rootDiag = if (prefs.rootEnabled) {
            RootDiagnostics.runRealRootTest()
        } else {
            RootDiagnosticResult(
                testedAtIso = RootDiagnostics.isoNow(),
                hasBeenTested = false,
                isRootAvailable = false,
                suUidOutput = "Mode ROOT désactivé dans les paramètres",
                detectedManager = "Non sollicité"
            )
        }

        val effectiveRoot = prefs.rootEnabled && rootDiag.isRootAvailable
        val perms = buildPermissionsState(appCtx, exporter, prefs.rootEnabled, rootDiag, isServiceActive = false)

        // 2. Reconnaissance de l'appareil et du type de ROM
        val profile = DeviceProfiler.collectProfile(appCtx, effectiveRoot)

        // 3. Capture de l'état initial (baseline)
        _state.value = _state.value.copy(
            permissions = perms,
            deviceProfile = profile,
            statusBanner = "Initialisation des collecteurs et capture de l'état initial..."
        )
        val (baseline, baseArtifacts) = UniversalFodCollectors.captureBaseline(appCtx, effectiveRoot)

        lastBioCode = baseline.biometricStatusCode
        lastBrightness = baseline.screenBrightness
        lastRefreshHz = baseline.refreshRateHz
        lastEnrolledCount = baseArtifacts.enrolledTemplateCount
        lastSysfsSnapshot = baseline.readableSysfsSnapshot

        val initEvent = UniversalFodCollectors.createSyntheticAppEvent(
            source = "SessionCoordinator",
            category = "SESSION_INIT",
            title = "État initial capturé (${profile.romType.label})",
            rawPayload = "Stockage=${perms.sdcardFodWritable}, ROOT=${effectiveRoot}, Propriétés=${baseArtifacts.matchedProperties.size}, Sysfs=${baseArtifacts.sysfsValues.size}",
            evidenceLevel = EvidenceLevel.FAIT_VERIFIE
        )

        val initialEvents = listOf(initEvent)
        val synthesis = CausalReconstructionEngine.synthesize(
            profile = profile,
            permissions = perms,
            artifacts = baseArtifacts,
            events = initialEvents,
            enrollmentSucceeded = false,
            failureEvidence = null
        )

        val exportState = exporter.exportFullSession(
            sessionId = currentSessionId,
            profile = profile,
            permissions = perms,
            baseline = baseline,
            events = initialEvents,
            components = synthesis.components,
            dependencies = synthesis.dependencies,
            modifications = synthesis.modifications,
            reconstruction = synthesis.reconstruction,
            artifacts = baseArtifacts
        )

        _state.value = FodEngineState(
            phase = EnginePhase.BASELINE_CAPTURED,
            permissions = perms,
            deviceProfile = profile,
            baseline = baseline,
            events = initialEvents,
            components = synthesis.components,
            dependencies = synthesis.dependencies,
            modifications = synthesis.modifications,
            reconstruction = synthesis.reconstruction,
            exportState = exportState,
            rawArtifacts = baseArtifacts,
            isBusyAction = false,
            statusBanner = "État initial capturé. Activation de la surveillance passive..."
        )

        // 4. Activation automatique de la surveillance passive
        if (prefs.autoStartMonitoring) {
            startMonitoring(appCtx)
        }
    }

    fun startMonitoring(context: Context) {
        val appCtx = context.applicationContext
        if (monitorJob?.isActive == true) return

        // Démarrer le service de premier plan (tolérant aux restrictions)
        val fgStarted = runCatching {
            val intent = Intent(appCtx, FodMonitoringService::class.java).apply {
                action = FodMonitoringService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(intent)
            } else {
                appCtx.startService(intent)
            }
            true
        }.getOrElse { e ->
            Log.w(TAG, "Service de premier plan non démarré (poursuite en mode actif): ${e.message}")
            false
        }

        _state.value = _state.value.copy(
            phase = if (deepWaveTriggered) EnginePhase.INVESTIGATING else EnginePhase.MONITORING_PASSIVE,
            permissions = _state.value.permissions.copy(isForegroundServiceActive = fgStarted),
            statusBanner = if (deepWaveTriggered) {
                "Investigation active : observation des interactions FOD..."
            } else {
                "Surveillance passive active : ouvrez les Paramètres Android pour enregistrer une empreinte."
            }
        )

        monitorJob = scope.launch {
            val exporter = FodStorageExporter(appCtx)
            val prefsManager = PreferencesManager(appCtx)
            var cycle = 0
            while (isActive) {
                val intervalMs = prefsManager.settingsFlow.first().pollingIntervalMs
                val effectiveRoot = _state.value.permissions.isEffectiveRootReady

                val delta = UniversalFodCollectors.pollIncrementalDeltas(
                    context = appCtx,
                    useRoot = effectiveRoot,
                    previousBioCode = lastBioCode,
                    previousBrightness = lastBrightness,
                    previousRefreshHz = lastRefreshHz,
                    previousSysfs = lastSysfsSnapshot,
                    previousEnrolledCount = lastEnrolledCount,
                    seenLogHashes = seenLogHashes
                )

                lastBioCode = delta.currentBioCode
                lastBrightness = delta.currentBrightness
                lastRefreshHz = delta.currentRefreshHz
                lastSysfsSnapshot = delta.updatedSysfs
                lastEnrolledCount = delta.currentEnrolledCount

                if (delta.enrollmentSucceeded) {
                    enrollmentSucceededFlag = true
                }
                if (delta.failureLine != null && firstFailureLine == null) {
                    firstFailureLine = delta.failureLine
                }

                val shouldTriggerWave = delta.investigationTriggered && !deepWaveTriggered
                if (shouldTriggerWave) {
                    deepWaveTriggered = true
                }

                if (delta.newEvents.isNotEmpty() || shouldTriggerWave) {
                    val updatedEvents = (_state.value.events + delta.newEvents).takeLast(400)
                    var updatedArtifacts = _state.value.rawArtifacts

                    if (shouldTriggerWave) {
                        _state.value = _state.value.copy(
                            phase = EnginePhase.INVESTIGATING,
                            events = updatedEvents,
                            statusBanner = "1er événement d'enregistrement détecté ! Investigation approfondie déclenchée..."
                        )
                        updatedArtifacts = mergeArtifacts(
                            updatedArtifacts,
                            UniversalFodCollectors.collectEnvironmentArtifacts(appCtx, effectiveRoot, wave = 1)
                        )
                    } else if (delta.enrollmentSucceeded) {
                        // Vague finale post-enregistrement pour capturer les preuves de résultat
                        updatedArtifacts = mergeArtifacts(
                            updatedArtifacts,
                            UniversalFodCollectors.collectEnvironmentArtifacts(appCtx, effectiveRoot, wave = 2)
                        )
                    }

                    val synthesis = CausalReconstructionEngine.synthesize(
                        profile = _state.value.deviceProfile,
                        permissions = _state.value.permissions,
                        artifacts = updatedArtifacts,
                        events = updatedEvents,
                        enrollmentSucceeded = enrollmentSucceededFlag,
                        failureEvidence = firstFailureLine
                    )

                    val nextPhase = when {
                        enrollmentSucceededFlag -> EnginePhase.RECONSTRUCTED
                        deepWaveTriggered -> EnginePhase.INVESTIGATING
                        else -> EnginePhase.MONITORING_PASSIVE
                    }

                    val exportState = exporter.exportFullSession(
                        sessionId = currentSessionId,
                        profile = _state.value.deviceProfile,
                        permissions = _state.value.permissions,
                        baseline = _state.value.baseline,
                        events = updatedEvents,
                        components = synthesis.components,
                        dependencies = synthesis.dependencies,
                        modifications = synthesis.modifications,
                        reconstruction = synthesis.reconstruction,
                        artifacts = updatedArtifacts
                    )

                    _state.value = _state.value.copy(
                        phase = nextPhase,
                        events = updatedEvents,
                        components = synthesis.components,
                        dependencies = synthesis.dependencies,
                        modifications = synthesis.modifications,
                        reconstruction = synthesis.reconstruction,
                        rawArtifacts = updatedArtifacts,
                        exportState = exportState,
                        statusBanner = when (nextPhase) {
                            EnginePhase.RECONSTRUCTED -> "Enregistrement réussi et mécanisme reconstruit dans ${exportState.sessionDirectoryPath}"
                            EnginePhase.INVESTIGATING -> "Investigation active (${updatedEvents.size} événements observés)..."
                            else -> _state.value.statusBanner
                        }
                    )
                }

                cycle++
                if (cycle % 10 == 0 && _state.value.phase == EnginePhase.INVESTIGATING) {
                    // Rafraîchissement léger périodique pendant l'investigation
                    val waveArtifacts = UniversalFodCollectors.collectEnvironmentArtifacts(appCtx, effectiveRoot, wave = 2)
                    val merged = mergeArtifacts(_state.value.rawArtifacts, waveArtifacts)
                    val synthesis = CausalReconstructionEngine.synthesize(
                        profile = _state.value.deviceProfile,
                        permissions = _state.value.permissions,
                        artifacts = merged,
                        events = _state.value.events,
                        enrollmentSucceeded = enrollmentSucceededFlag,
                        failureEvidence = firstFailureLine
                    )
                    _state.value = _state.value.copy(
                        components = synthesis.components,
                        dependencies = synthesis.dependencies,
                        modifications = synthesis.modifications,
                        reconstruction = synthesis.reconstruction,
                        rawArtifacts = merged
                    )
                }

                delay(if (deepWaveTriggered) intervalMs else (intervalMs + 500L))
            }
        }
    }

    fun stopMonitoringLoop(context: Context) {
        monitorJob?.cancel()
        monitorJob = null
        runCatching {
            val intent = Intent(context.applicationContext, FodMonitoringService::class.java).apply {
                action = FodMonitoringService.ACTION_STOP
            }
            context.applicationContext.startService(intent)
        }
        _state.value = _state.value.copy(
            phase = EnginePhase.PAUSED,
            permissions = _state.value.permissions.copy(isForegroundServiceActive = false),
            statusBanner = "Surveillance passive suspendue."
        )
    }

    fun onSettingsEnrollLaunched(context: Context, targetAction: String) {
        settingsOpenedDuringSession = true
        val evt = UniversalFodCollectors.createSyntheticAppEvent(
            source = "AndroidSettingsLauncher",
            category = "SETTINGS_OPENED",
            title = "Ouverture des Paramètres Android pour enregistrement",
            rawPayload = "Intent lancé: $targetAction",
            evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
            triggersInvestigation = true
        )
        scope.launch {
            val updatedEvents = _state.value.events + evt
            if (!deepWaveTriggered) {
                deepWaveTriggered = true
            }
            _state.value = _state.value.copy(
                phase = EnginePhase.INVESTIGATING,
                events = updatedEvents,
                statusBanner = "Paramètres ouverts — surveillance active en arrière-plan..."
            )
            if (monitorJob?.isActive != true) {
                startMonitoring(context)
            }
        }
    }

    fun onAppResumed(context: Context) {
        val appCtx = context.applicationContext
        refreshPermissionsAndExportStatus(appCtx)
        if (settingsOpenedDuringSession) {
            scope.launch {
                val effectiveRoot = _state.value.permissions.isEffectiveRootReady
                val delta = UniversalFodCollectors.pollIncrementalDeltas(
                    context = appCtx,
                    useRoot = effectiveRoot,
                    previousBioCode = lastBioCode,
                    previousBrightness = lastBrightness,
                    previousRefreshHz = lastRefreshHz,
                    previousSysfs = lastSysfsSnapshot,
                    previousEnrolledCount = lastEnrolledCount,
                    seenLogHashes = seenLogHashes
                )
                lastBioCode = delta.currentBioCode
                lastBrightness = delta.currentBrightness
                lastRefreshHz = delta.currentRefreshHz
                lastSysfsSnapshot = delta.updatedSysfs
                lastEnrolledCount = delta.currentEnrolledCount
                if (delta.enrollmentSucceeded) enrollmentSucceededFlag = true
                if (delta.failureLine != null && firstFailureLine == null) firstFailureLine = delta.failureLine

                val resumeEvent = UniversalFodCollectors.createSyntheticAppEvent(
                    source = "SessionLifecycle",
                    category = "APP_RESUMED",
                    title = "Retour depuis les Paramètres Android",
                    rawPayload = "Code biométrique actuel: ${delta.currentBioCode}, nouveaux événements: ${delta.newEvents.size}",
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE
                )
                val updatedEvents = (_state.value.events + delta.newEvents + resumeEvent).takeLast(400)
                val waveArtifacts = mergeArtifacts(
                    _state.value.rawArtifacts,
                    UniversalFodCollectors.collectEnvironmentArtifacts(appCtx, effectiveRoot, wave = 2)
                )
                val synthesis = CausalReconstructionEngine.synthesize(
                    profile = _state.value.deviceProfile,
                    permissions = _state.value.permissions,
                    artifacts = waveArtifacts,
                    events = updatedEvents,
                    enrollmentSucceeded = enrollmentSucceededFlag,
                    failureEvidence = firstFailureLine
                )
                val exporter = FodStorageExporter(appCtx)
                val exportState = exporter.exportFullSession(
                    sessionId = currentSessionId,
                    profile = _state.value.deviceProfile,
                    permissions = _state.value.permissions,
                    baseline = _state.value.baseline,
                    events = updatedEvents,
                    components = synthesis.components,
                    dependencies = synthesis.dependencies,
                    modifications = synthesis.modifications,
                    reconstruction = synthesis.reconstruction,
                    artifacts = waveArtifacts
                )
                _state.value = _state.value.copy(
                    phase = if (enrollmentSucceededFlag) EnginePhase.RECONSTRUCTED else _state.value.phase,
                    events = updatedEvents,
                    components = synthesis.components,
                    dependencies = synthesis.dependencies,
                    modifications = synthesis.modifications,
                    reconstruction = synthesis.reconstruction,
                    rawArtifacts = waveArtifacts,
                    exportState = exportState
                )
            }
        }
    }

    fun refreshPermissionsAndExportStatus(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            val prefs = PreferencesManager(appCtx).settingsFlow.first()
            val exporter = FodStorageExporter(appCtx)
            val perms = buildPermissionsState(
                context = appCtx,
                exporter = exporter,
                rootEnabled = prefs.rootEnabled,
                rootDiag = _state.value.permissions.rootDiagnostic,
                isServiceActive = _state.value.permissions.isForegroundServiceActive
            )
            _state.value = _state.value.copy(permissions = perms)
        }
    }

    suspend fun setRootEnabledAndRefresh(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        PreferencesManager(appCtx).setRootEnabled(enabled)
        if (enabled) {
            runManualRootTest(appCtx)
        } else {
            val exporter = FodStorageExporter(appCtx)
            val perms = buildPermissionsState(
                context = appCtx,
                exporter = exporter,
                rootEnabled = false,
                rootDiag = RootDiagnosticResult(
                    testedAtIso = RootDiagnostics.isoNow(),
                    hasBeenTested = false,
                    isRootAvailable = false,
                    suUidOutput = "Mode ROOT désactivé par l'utilisateur",
                    detectedManager = "Désactivé"
                ),
                isServiceActive = _state.value.permissions.isForegroundServiceActive
            )
            _state.value = _state.value.copy(
                permissions = perms,
                statusBanner = "Mode ROOT désactivé : poursuite avec les collecteurs standard."
            )
        }
    }

    suspend fun runManualRootTest(context: Context) {
        val appCtx = context.applicationContext
        _state.value = _state.value.copy(isBusyAction = true, statusBanner = "Test des privilèges ROOT (su -c id)...")
        val prefs = PreferencesManager(appCtx).settingsFlow.first()
        val diag = RootDiagnostics.runRealRootTest()
        val exporter = FodStorageExporter(appCtx)
        val perms = buildPermissionsState(
            context = appCtx,
            exporter = exporter,
            rootEnabled = prefs.rootEnabled || diag.isRootAvailable,
            rootDiag = diag,
            isServiceActive = _state.value.permissions.isForegroundServiceActive
        )
        if (diag.isRootAvailable && !prefs.rootEnabled) {
            PreferencesManager(appCtx).setRootEnabled(true)
        }
        val profile = DeviceProfiler.collectProfile(appCtx, perms.isEffectiveRootReady)
        val newArtifacts = mergeArtifacts(
            _state.value.rawArtifacts,
            UniversalFodCollectors.collectEnvironmentArtifacts(appCtx, perms.isEffectiveRootReady, wave = 1)
        )
        val testEvt = UniversalFodCollectors.createSyntheticAppEvent(
            source = "RootDiagnostics",
            category = "ROOT_TEST",
            title = if (diag.isRootAvailable) "Privilèges ROOT vérifiés (${diag.detectedManager})" else "Test ROOT sans élévation",
            rawPayload = "uid=${diag.suUidOutput} | SELinux=${diag.selinuxMode} | ${diag.errorDetail}",
            evidenceLevel = EvidenceLevel.FAIT_VERIFIE
        )
        val updatedEvents = _state.value.events + testEvt
        val synthesis = CausalReconstructionEngine.synthesize(
            profile = profile,
            permissions = perms,
            artifacts = newArtifacts,
            events = updatedEvents,
            enrollmentSucceeded = enrollmentSucceededFlag,
            failureEvidence = firstFailureLine
        )
        val exportState = exporter.exportFullSession(
            sessionId = currentSessionId.ifBlank { exporter.createSessionId().also { currentSessionId = it } },
            profile = profile,
            permissions = perms,
            baseline = _state.value.baseline,
            events = updatedEvents,
            components = synthesis.components,
            dependencies = synthesis.dependencies,
            modifications = synthesis.modifications,
            reconstruction = synthesis.reconstruction,
            artifacts = newArtifacts
        )
        _state.value = _state.value.copy(
            permissions = perms,
            deviceProfile = profile,
            events = updatedEvents,
            components = synthesis.components,
            dependencies = synthesis.dependencies,
            modifications = synthesis.modifications,
            reconstruction = synthesis.reconstruction,
            rawArtifacts = newArtifacts,
            exportState = exportState,
            isBusyAction = false,
            statusBanner = if (diag.isRootAvailable) {
                "Accès ROOT vérifié (${diag.detectedManager}). Collecteurs profonds actifs."
            } else {
                "Accès ROOT indisponible : poursuite en mode limité sans blocage."
            }
        )
    }

    suspend fun finalizeAndReconstruct(context: Context) {
        val appCtx = context.applicationContext
        _state.value = _state.value.copy(isBusyAction = true, statusBanner = "Consolidation et export final du mécanisme...")
        val effectiveRoot = _state.value.permissions.isEffectiveRootReady
        val finalArtifacts = mergeArtifacts(
            _state.value.rawArtifacts,
            UniversalFodCollectors.collectEnvironmentArtifacts(appCtx, effectiveRoot, wave = 2)
        )
        val synthesis = CausalReconstructionEngine.synthesize(
            profile = _state.value.deviceProfile,
            permissions = _state.value.permissions,
            artifacts = finalArtifacts,
            events = _state.value.events,
            enrollmentSucceeded = enrollmentSucceededFlag,
            failureEvidence = firstFailureLine
        )
        val exporter = FodStorageExporter(appCtx)
        val exportState = exporter.exportFullSession(
            sessionId = currentSessionId.ifBlank { exporter.createSessionId().also { currentSessionId = it } },
            profile = _state.value.deviceProfile,
            permissions = _state.value.permissions,
            baseline = _state.value.baseline,
            events = _state.value.events,
            components = synthesis.components,
            dependencies = synthesis.dependencies,
            modifications = synthesis.modifications,
            reconstruction = synthesis.reconstruction,
            artifacts = finalArtifacts
        )
        _state.value = _state.value.copy(
            phase = EnginePhase.RECONSTRUCTED,
            components = synthesis.components,
            dependencies = synthesis.dependencies,
            modifications = synthesis.modifications,
            reconstruction = synthesis.reconstruction,
            rawArtifacts = finalArtifacts,
            exportState = exportState,
            isBusyAction = false,
            statusBanner = "Reconstruction finalisée : ${exportState.sessionDirectoryPath}"
        )
    }

    suspend fun syncPendingExportToSdcard(context: Context): String {
        val exporter = FodStorageExporter(context.applicationContext)
        val res = exporter.syncFallbackToSdcard(currentSessionId)
        return res.fold(
            onSuccess = { path ->
                refreshPermissionsAndExportStatus(context)
                _state.value = _state.value.copy(
                    exportState = _state.value.exportState.copy(
                        sessionDirectoryPath = path,
                        isPrimarySdcardFod = true,
                        pendingSyncToSdcard = false,
                        exportMessage = "Synchronisé vers $path"
                    )
                )
                "Session copiée vers $path"
            },
            onFailure = { err ->
                err.localizedMessage ?: "Échec de synchronisation vers /sdcard/FOD/"
            }
        )
    }

    private fun buildPermissionsState(
        context: Context,
        exporter: FodStorageExporter,
        rootEnabled: Boolean,
        rootDiag: RootDiagnosticResult,
        isServiceActive: Boolean
    ): PermissionsState {
        val storageGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }
        val sdcardWritable = exporter.isSdcardFodAccessible()

        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val limitations = mutableListOf<CapabilityLimitation>()
        if (!sdcardWritable) {
            limitations.add(
                CapabilityLimitation(
                    code = "STORAGE_SDCARD_FOD_RESTRICTED",
                    title = "Accès direct à /sdcard/FOD/ non accordé",
                    impact = "Les rapports sont écrits dans le répertoire de repli sécurisé de l'application jusqu'à l'octroi de la permission Tous les fichiers.",
                    requiresStorage = true
                )
            )
        }
        if (!rootEnabled || !rootDiag.isRootAvailable) {
            limitations.add(
                CapabilityLimitation(
                    code = if (!rootEnabled) "ROOT_DISABLED" else "ROOT_UNAVAILABLE",
                    title = if (!rootEnabled) "Privilèges ROOT désactivés" else "Privilèges ROOT non obtenus",
                    impact = "Les flux logcat système/kernel, dumpsys fingerprint/biometric, lshal, /vendor/etc/init, overlays RRO et /data/adb/modules ne sont pas directement lisibles.",
                    requiresRoot = true
                )
            )
        }
        if (!notifGranted) {
            limitations.add(
                CapabilityLimitation(
                    code = "NOTIF_DENIED",
                    title = "Notifications non autorisées",
                    impact = "Le statut en direct du service de premier plan peut rester masqué dans la barre système.",
                    requiresRoot = false
                )
            )
        }

        return PermissionsState(
            storagePermissionGranted = storageGranted,
            sdcardFodWritable = sdcardWritable,
            activeStorageRootPath = if (sdcardWritable) exporter.getPrimarySdcardFodDir().absolutePath else exporter.getFallbackFodDir().absolutePath,
            fallbackStoragePath = exporter.getFallbackFodDir().absolutePath,
            rootEnabledByUser = rootEnabled,
            rootDiagnostic = rootDiag,
            notificationsGranted = notifGranted,
            isForegroundServiceActive = isServiceActive,
            limitations = limitations
        )
    }

    private fun mergeArtifacts(base: RawCollectorArtifacts, extra: RawCollectorArtifacts): RawCollectorArtifacts {
        return RawCollectorArtifacts(
            rawDumps = base.rawDumps + extra.rawDumps,
            matchedProperties = base.matchedProperties + extra.matchedProperties,
            sysfsValues = base.sysfsValues + extra.sysfsValues,
            discoveredServices = (base.discoveredServices + extra.discoveredServices).distinct(),
            discoveredHalFiles = (base.discoveredHalFiles + extra.discoveredHalFiles).distinct(),
            discoveredInitFiles = (base.discoveredInitFiles + extra.discoveredInitFiles).distinct(),
            discoveredOverlays = (base.discoveredOverlays + extra.discoveredOverlays).distinct(),
            discoveredModules = (base.discoveredModules + extra.discoveredModules).distinct(),
            resolvedSettingsTargets = (base.resolvedSettingsTargets + extra.resolvedSettingsTargets).distinct(),
            enrolledTemplateCount = extra.enrolledTemplateCount ?: base.enrolledTemplateCount,
            logcatLines = (base.logcatLines + extra.logcatLines).distinct().takeLast(300),
            collectorNotes = (base.collectorNotes + extra.collectorNotes).distinct()
        )
    }
}
