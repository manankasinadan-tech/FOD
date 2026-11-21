package com.example.data

import android.content.Context
import android.os.Build
import android.os.Environment
import com.example.engine.RawCollectorArtifacts
import com.example.engine.RootDiagnostics
import com.example.model.BaselineSnapshot
import com.example.model.ComponentDependency
import com.example.model.DetectedModification
import com.example.model.DeviceProfile
import com.example.model.DiscoveredComponent
import com.example.model.ExportSessionState
import com.example.model.ExportedFileEntry
import com.example.model.FodEvent
import com.example.model.MechanismReconstruction
import com.example.model.PermissionsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FodStorageExporter(private val context: Context) {

    fun isSdcardFodAccessible(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            return false
        }
        return runCatching {
            val dir = File(Environment.getExternalStorageDirectory(), "FOD")
            if (!dir.exists()) dir.mkdirs()
            dir.exists() && dir.canWrite()
        }.getOrDefault(false)
    }

    fun getPrimarySdcardFodDir(): File = File(Environment.getExternalStorageDirectory(), "FOD")

    fun getFallbackFodDir(): File = context.getExternalFilesDir("FOD") ?: File(context.filesDir, "FOD")

    fun createSessionId(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "session_$stamp"
    }

    suspend fun exportFullSession(
        sessionId: String,
        profile: DeviceProfile,
        permissions: PermissionsState,
        baseline: BaselineSnapshot?,
        events: List<FodEvent>,
        components: List<DiscoveredComponent>,
        dependencies: List<ComponentDependency>,
        modifications: List<DetectedModification>,
        reconstruction: MechanismReconstruction?,
        artifacts: RawCollectorArtifacts
    ): ExportSessionState = withContext(Dispatchers.IO) {
        val usePrimary = isSdcardFodAccessible()
        val rootDir = if (usePrimary) getPrimarySdcardFodDir() else getFallbackFodDir()
        val sessionDir = File(rootDir, sessionId)
        val evidenceDir = File(sessionDir, "evidence")

        runCatching {
            sessionDir.mkdirs()
            evidenceDir.mkdirs()
        }

        val entries = mutableListOf<ExportedFileEntry>()

        fun writeFile(relPath: String, content: String, category: String) {
            val target = File(sessionDir, relPath)
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
            entries.add(ExportedFileEntry(relPath, target.length(), category))
        }

        // 1. Profil appareil
        writeFile("device_profile.json", deviceProfileToJson(profile).toString(2), "Profil")

        // 2. Autorisations & limitations
        writeFile("permissions_and_limits.json", permissionsToJson(permissions).toString(2), "Capacités")

        // 3. État initial
        baseline?.let {
            writeFile("baseline_state.json", baselineToJson(it).toString(2), "Référence")
        }

        // 4. Événements horodatés
        writeFile("events.json", eventsToJson(events).toString(2), "Événements")

        // 5. Composants découverts
        writeFile("components.json", componentsToJson(components).toString(2), "Composants")

        // 6. Dépendances
        writeFile("dependencies.json", dependenciesToJson(dependencies).toString(2), "Dépendances")

        // 7. Patches, hooks & solutions alternatives
        writeFile("patches_and_hooks.json", modificationsToJson(modifications).toString(2), "Modifications")

        // 8. Cartographie du mécanisme
        reconstruction?.let {
            writeFile("mechanism_mapping.json", reconstructionToJson(it).toString(2), "Cartographie")
        }

        // 9. Preuves brutes et ressources accessibles
        val accessibleResourcesJson = JSONObject().apply {
            put("settings_targets", JSONArray(artifacts.resolvedSettingsTargets))
            put("matched_properties_count", artifacts.matchedProperties.size)
            put("sysfs_nodes_count", artifacts.sysfsValues.size)
            put("hal_files_count", artifacts.discoveredHalFiles.size)
            put("collector_notes", JSONArray(artifacts.collectorNotes))
        }
        writeFile("evidence/accessible_resources.json", accessibleResourcesJson.toString(2), "Preuves")

        artifacts.rawDumps.forEach { (filename, body) ->
            writeFile("evidence/$filename", body, "Preuves brutes")
        }

        // 10. Rapport maître consolidé
        val masterReport = JSONObject().apply {
            put("schema_version", "1.0")
            put("session_id", sessionId)
            put("generated_at", RootDiagnostics.isoNow())
            put("storage_target", sessionDir.absolutePath)
            put("is_primary_sdcard_fod", usePrimary)
            put("device", deviceProfileToJson(profile))
            put("permissions", permissionsToJson(permissions))
            put("summary_counts", JSONObject().apply {
                put("events", events.size)
                put("components", components.size)
                put("dependencies", dependencies.size)
                put("modifications", modifications.size)
            })
            reconstruction?.let { put("mechanism_reconstruction", reconstructionToJson(it)) }
        }
        writeFile("report.json", masterReport.toString(2), "Rapport maître")

        ExportSessionState(
            sessionId = sessionId,
            sessionDirectoryPath = sessionDir.absolutePath,
            isPrimarySdcardFod = usePrimary,
            pendingSyncToSdcard = !usePrimary,
            exportedFiles = entries,
            lastExportIso = RootDiagnostics.isoNow(),
            exportMessage = if (usePrimary) {
                "Enregistré dans /sdcard/FOD/$sessionId"
            } else {
                "Permission /sdcard/FOD/ absente : sauvegardé en zone de repli (${sessionDir.absolutePath}), prêt à synchroniser."
            }
        )
    }

    suspend fun syncFallbackToSdcard(sessionId: String): Result<String> = withContext(Dispatchers.IO) {
        if (!isSdcardFodAccessible()) {
            return@withContext Result.failure(IllegalStateException("Accès complet au stockage (/sdcard/FOD/) non accordé."))
        }
        val src = File(getFallbackFodDir(), sessionId)
        if (!src.exists()) {
            return@withContext Result.failure(IllegalStateException("Aucune session en attente de synchronisation."))
        }
        val dst = File(getPrimarySdcardFodDir(), sessionId)
        dst.mkdirs()
        src.copyRecursively(dst, overwrite = true)
        Result.success(dst.absolutePath)
    }

    private fun deviceProfileToJson(p: DeviceProfile) = JSONObject().apply {
        put("brand", p.brand)
        put("manufacturer", p.manufacturer)
        put("model", p.model)
        put("device", p.device)
        put("board", p.board)
        put("hardware", p.hardware)
        put("soc_model", p.socModel)
        put("abis", JSONArray(p.abis))
        put("android_release", p.androidRelease)
        put("sdk_int", p.sdkInt)
        put("security_patch", p.securityPatch)
        put("build_fingerprint", p.buildFingerprint)
        put("rom_type", p.romType.name)
        put("rom_label", p.romType.label)
        put("rom_evidence", JSONArray(p.romEvidence))
        put("selinux_mode", p.selinuxMode)
        put("has_fingerprint_feature", p.hasFingerprintFeature)
        put("biometric_status_code", p.biometricStatusCode)
        put("biometric_status_label", p.biometricStatusLabel)
        put("display_resolution", p.displayResolution)
        put("display_refresh_hz", p.displayRefreshRateHz.toDouble())
        put("display_hdr_types", JSONArray(p.displayHdrTypes))
    }

    private fun permissionsToJson(p: PermissionsState) = JSONObject().apply {
        put("storage_permission_granted", p.storagePermissionGranted)
        put("sdcard_fod_writable", p.sdcardFodWritable)
        put("root_enabled_by_user", p.rootEnabledByUser)
        put("root_available", p.rootDiagnostic.isRootAvailable)
        put("root_manager", p.rootDiagnostic.detectedManager)
        put("su_uid_output", p.rootDiagnostic.suUidOutput)
        put("notifications_granted", p.notificationsGranted)
        put("limitations", JSONArray().apply {
            p.limitations.forEach { lim ->
                put(JSONObject().apply {
                    put("code", lim.code)
                    put("title", lim.title)
                    put("impact", lim.impact)
                })
            }
        })
    }

    private fun baselineToJson(b: BaselineSnapshot) = JSONObject().apply {
        put("timestamp_iso", b.timestampIso)
        put("biometric_status_code", b.biometricStatusCode)
        put("biometric_status_label", b.biometricStatusLabel)
        put("screen_brightness", b.screenBrightness)
        put("refresh_rate_hz", b.refreshRateHz.toDouble())
        put("matched_properties", JSONObject(b.keyProperties))
        put("sysfs_snapshot", JSONObject(b.readableSysfsSnapshot))
    }

    private fun eventsToJson(events: List<FodEvent>) = JSONArray().apply {
        events.forEach { e ->
            put(JSONObject().apply {
                put("id", e.id)
                put("timestamp_ms", e.timestampMs)
                put("timestamp_iso", e.timestampIso)
                put("source", e.source)
                put("category", e.category)
                put("title", e.title)
                put("evidence_level", e.evidenceLevel.name)
                put("triggers_investigation", e.triggersInvestigation)
                put("raw_payload", e.rawPayload)
            })
        }
    }

    private fun componentsToJson(items: List<DiscoveredComponent>) = JSONArray().apply {
        items.forEach { c ->
            put(JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("category", c.category.name)
                put("layer", c.layer.name)
                put("identifier", c.pathOrIdentifier)
                put("status", c.status)
                put("evidence_level", c.evidenceLevel.name)
                put("discovery_wave", c.discoveryWave)
                put("raw_proof", c.rawProof)
            })
        }
    }

    private fun dependenciesToJson(items: List<ComponentDependency>) = JSONArray().apply {
        items.forEach { d ->
            put(JSONObject().apply {
                put("id", d.id)
                put("from", d.fromComponentName)
                put("to", d.toComponentName)
                put("relation", d.relationType)
                put("evidence_level", d.evidenceLevel.name)
                put("raw_proof", d.rawProof)
            })
        }
    }

    private fun modificationsToJson(items: List<DetectedModification>) = JSONArray().apply {
        items.forEach { m ->
            put(JSONObject().apply {
                put("id", m.id)
                put("type", m.type.name)
                put("name", m.name)
                put("target", m.target)
                put("role_summary", m.roleSummary)
                put("evidence_level", m.evidenceLevel.name)
                put("raw_proof", m.rawProof)
            })
        }
    }

    private fun reconstructionToJson(r: MechanismReconstruction) = JSONObject().apply {
        put("outcome", r.outcome.name)
        put("outcome_label", r.outcome.label)
        put("summary", r.summary)
        put("causal_execution_path", JSONArray(r.causalExecutionPath))
        put("first_break_description", r.firstBreakDescription)
        put("first_break_proof", r.firstBreakProof)
        put("verified_facts_count", r.verifiedFactsCount)
        put("observed_correlations_count", r.observedCorrelationsCount)
        put("limitations_count", r.limitationsCount)
        put("generated_at", r.generatedAtIso)
        put("stages", JSONArray().apply {
            r.stages.forEach { s ->
                put(JSONObject().apply {
                    put("layer", s.layer.name)
                    put("layer_label", s.layer.label)
                    put("status", s.status.name)
                    put("components_count", s.componentsCount)
                    put("components", JSONArray(s.componentNames))
                    put("summary", s.factualSummary)
                    put("raw_proofs", JSONArray(s.rawProofs))
                })
            }
        })
    }
}
