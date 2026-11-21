package com.example.data

import android.content.Context
import android.os.Build
import android.os.Environment
import com.example.engine.RawCollectorArtifacts
import com.example.engine.RootDiagnostics
import com.example.model.BaselineSnapshot
import com.example.model.ComponentCategory
import com.example.model.ComponentDependency
import com.example.model.DetectedModification
import com.example.model.DeviceProfile
import com.example.model.DiscoveredComponent
import com.example.model.EvidenceLevel
import com.example.model.ExportSessionState
import com.example.model.ExportedFileEntry
import com.example.model.FlattenedHalArtifact
import com.example.model.FodEvent
import com.example.model.ImportedFodSession
import com.example.model.MechanismLayer
import com.example.model.MechanismReconstruction
import com.example.model.ModificationType
import com.example.model.PermissionsState
import com.example.model.ReconstructionOutcome
import com.example.model.RomType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FodStorageExporter(private val context: Context) {

    fun isSdcardFodAccessible(): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                return false
            }
            val dir = File(Environment.getExternalStorageDirectory(), "FOD")
            if (!dir.exists()) dir.mkdirs()
            dir.exists() && dir.canWrite()
        }.getOrDefault(false)
    }

    fun getPrimarySdcardFodDir(): File = runCatching {
        File(Environment.getExternalStorageDirectory(), "FOD")
    }.getOrElse { File("/sdcard/FOD") }

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
        flattenedHals: List<FlattenedHalArtifact>,
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
            // Nettoyage strict pour garantir exactement 3 JSON max + 1 seul TXT dans evidence/
            sessionDir.listFiles()?.filter { it.isFile && it.extension.equals("json", true) }?.forEach { it.delete() }
            evidenceDir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
        }

        val entries = mutableListOf<ExportedFileEntry>()

        fun writeFile(relPath: String, content: String, category: String) {
            val target = File(sessionDir, relPath)
            target.parentFile?.mkdirs()
            target.writeText(content, Charsets.UTF_8)
            entries.add(ExportedFileEntry(relPath, target.length(), category))
        }

        // JSON 1 : Vue d'ensemble, profil appareil/ROM, permissions, baseline et mécanisme reconstruit
        val json1Overview = JSONObject().apply {
            put("schema_version", "2.0")
            put("session_id", sessionId)
            put("generated_at", RootDiagnostics.isoNow())
            put("storage_target", sessionDir.absolutePath)
            put("is_primary_sdcard_fod", usePrimary)
            put("device_profile", deviceProfileToJson(profile))
            put("permissions_and_limits", permissionsToJson(permissions))
            baseline?.let { put("baseline_state", baselineToJson(it)) }
            reconstruction?.let { put("mechanism_reconstruction", reconstructionToJson(it)) }
            put("summary_counts", JSONObject().apply {
                put("events", events.size)
                put("components", components.size)
                put("flattened_hals", flattenedHals.size)
                put("dependencies", dependencies.size)
                put("modifications", modifications.size)
            })
        }
        writeFile("1_overview_and_mechanism.json", json1Overview.toString(2), "JSON 1 — Synthèse & Mécanisme")

        // JSON 2 : HAL mis à plat, composants, dépendances, .rc, permissions XML, sysfs, propriétés et patches
        val json2HalAndComponents = JSONObject().apply {
            put("session_id", sessionId)
            put("flattened_hals", flattenedHalsToJson(flattenedHals))
            put("components", componentsToJson(components))
            put("dependencies", dependenciesToJson(dependencies))
            put("modifications_and_patches", modificationsToJson(modifications))
            put("properties", JSONObject(artifacts.matchedProperties))
            put("sysfs_nodes", JSONObject(artifacts.sysfsValues))
            put("rc_files", JSONObject(artifacts.halBundle.rcFileContents))
            put("permission_xmls", JSONObject(artifacts.halBundle.permissionXmlContents))
            put("vintf_manifests", JSONObject(artifacts.halBundle.vintfManifestContents))
            put("selinux_contexts", JSONArray(artifacts.halBundle.selinuxContextLines))
        }
        writeFile("2_hal_components_and_porting.json", json2HalAndComponents.toString(2), "JSON 2 — HAL à plat & Portage")

        // JSON 3 : Chronologie complète des événements, déclencheurs et ressources accessibles
        val json3Events = JSONObject().apply {
            put("session_id", sessionId)
            put("events_count", events.size)
            put("events", eventsToJson(events))
            put("accessible_resources", JSONObject().apply {
                put("settings_targets", JSONArray(artifacts.resolvedSettingsTargets))
                put("discovered_services", JSONArray(artifacts.discoveredServices))
                put("discovered_hal_files", JSONArray(artifacts.discoveredHalFiles))
                put("discovered_overlays", JSONArray(artifacts.discoveredOverlays))
                put("discovered_modules", JSONArray(artifacts.discoveredModules))
                put("collector_notes", JSONArray(artifacts.collectorNotes))
            })
        }
        writeFile("3_events_and_timeline.json", json3Events.toString(2), "JSON 3 — Chronologie & Ressources")

        // Fichier TXT unique dans evidence/ rassemblant toutes les preuves brutes
        val consolidatedEvidence = buildConsolidatedEvidenceTxt(
            sessionId = sessionId,
            profile = profile,
            permissions = permissions,
            reconstruction = reconstruction,
            flattenedHals = flattenedHals,
            artifacts = artifacts
        )
        writeFile("evidence/all_evidence.txt", consolidatedEvidence, "Preuve unique consolidée (.txt)")

        // Export ZIP automatique de la session à côté du dossier session
        val zipFile = File(rootDir, "$sessionId.zip")
        val zipWritten = runCatching {
            writeDirectoryToZip(sessionDir, FileOutputStream(zipFile))
            zipFile.absolutePath
        }.getOrDefault("")

        ExportSessionState(
            sessionId = sessionId,
            sessionDirectoryPath = sessionDir.absolutePath,
            zipFilePath = zipWritten,
            isPrimarySdcardFod = usePrimary,
            pendingSyncToSdcard = !usePrimary,
            exportedFiles = entries,
            lastExportIso = RootDiagnostics.isoNow(),
            exportMessage = if (usePrimary) {
                "3 JSON + 1 evidence/all_evidence.txt enregistrés dans /sdcard/FOD/$sessionId"
            } else {
                "Permission /sdcard/FOD/ absente : sauvegardé en zone de repli (${sessionDir.absolutePath}), prêt à synchroniser."
            }
        )
    }

    suspend fun exportZipToStream(sessionId: String, outputStream: OutputStream): Result<Long> = withContext(Dispatchers.IO) {
        val dir = findSessionDir(sessionId) ?: return@withContext Result.failure(
            IllegalStateException("Dossier de session introuvable pour $sessionId.")
        )
        runCatching {
            val bytes = writeDirectoryToZipBytes(dir)
            outputStream.use { it.write(bytes) }
            bytes.size.toLong()
        }
    }

    suspend fun listLocalZipFiles(): List<File> = withContext(Dispatchers.IO) {
        val dirs = listOf(getPrimarySdcardFodDir(), getFallbackFodDir())
        dirs.flatMap { d ->
            runCatching {
                d.listFiles()?.filter { it.isFile && it.extension.equals("zip", true) }.orEmpty()
            }.getOrDefault(emptyList())
        }.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
    }

    suspend fun importSessionFromZip(inputStream: InputStream, sourceLabel: String): Result<ImportedFodSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val jsonFiles = linkedMapOf<String, String>()
                ZipInputStream(inputStream).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)) {
                            val name = entry.name.substringAfterLast('/')
                            val content = zis.readBytes().toString(Charsets.UTF_8)
                            jsonFiles[name] = content
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                if (jsonFiles.isEmpty()) {
                    error("L'archive ZIP ne contient aucun rapport JSON FOD Detector valide.")
                }

                parseImportedSession(jsonFiles, sourceLabel)
            }
        }

    suspend fun importSessionFromLocalZip(file: File): Result<ImportedFodSession> = withContext(Dispatchers.IO) {
        runCatching {
            FileInputStream(file).use { fis ->
                importSessionFromZip(fis, file.name).getOrThrow()
            }
        }
    }

    fun buildImportedSessionFromLive(
        sessionId: String,
        profile: DeviceProfile,
        permissions: PermissionsState,
        components: List<DiscoveredComponent>,
        modifications: List<DetectedModification>,
        flattenedHals: List<FlattenedHalArtifact>,
        reconstruction: MechanismReconstruction?,
        artifacts: RawCollectorArtifacts,
        eventsCount: Int
    ): ImportedFodSession {
        return ImportedFodSession(
            sourceLabel = "Session courante ($sessionId)",
            sessionId = sessionId,
            manufacturerModel = "${profile.manufacturer} ${profile.model}".trim().ifBlank { "Appareil courant" },
            romType = profile.romType,
            romLabel = profile.romType.label,
            androidRelease = profile.androidRelease,
            sdkInt = profile.sdkInt,
            rootActive = permissions.isEffectiveRootReady,
            outcome = reconstruction?.outcome ?: ReconstructionOutcome.OBSERVATION_PARTIELLE,
            outcomeLabel = reconstruction?.outcome?.label ?: "En cours",
            mechanismSummary = reconstruction?.summary.orEmpty(),
            causalExecutionPath = reconstruction?.causalExecutionPath.orEmpty(),
            firstBreakDescription = reconstruction?.firstBreakDescription,
            firstBreakProof = reconstruction?.firstBreakProof,
            flattenedHals = flattenedHals,
            components = components,
            modifications = modifications,
            properties = artifacts.matchedProperties,
            sysfsNodes = artifacts.sysfsValues,
            rcBlocks = artifacts.halBundle.rcFileContents.entries.map { "${it.key}:\n${it.value}" },
            permissionEntries = artifacts.halBundle.permissionXmlContents.entries.map { "${it.key}:\n${it.value}" },
            eventsCount = eventsCount
        )
    }

    private fun findSessionDir(sessionId: String): File? {
        val primary = File(getPrimarySdcardFodDir(), sessionId)
        if (primary.exists() && primary.isDirectory) return primary
        val fallback = File(getFallbackFodDir(), sessionId)
        if (fallback.exists() && fallback.isDirectory) return fallback
        return null
    }

    private fun writeDirectoryToZip(dir: File, out: OutputStream) {
        val bytes = writeDirectoryToZipBytes(dir)
        out.use { it.write(bytes) }
    }

    private fun writeDirectoryToZipBytes(dir: File): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            for (file in files) {
                val rel = file.relativeTo(dir).path.replace("\\", "/")
                zos.putNextEntry(ZipEntry(rel))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun buildConsolidatedEvidenceTxt(
        sessionId: String,
        profile: DeviceProfile,
        permissions: PermissionsState,
        reconstruction: MechanismReconstruction?,
        flattenedHals: List<FlattenedHalArtifact>,
        artifacts: RawCollectorArtifacts
    ): String = buildString {
        appendLine("######################################################################")
        appendLine("# FOD DETECTOR — EVIDENCE CONSOLIDÉE UNIQUE (LECTURE SEULE)")
        appendLine("# Session : $sessionId | Généré : ${RootDiagnostics.isoNow()}")
        appendLine("# Appareil : ${profile.manufacturer} ${profile.model} | ROM : ${profile.romType.label}")
        appendLine("# Android : ${profile.androidRelease} (API ${profile.sdkInt}) | ROOT actif : ${permissions.isEffectiveRootReady}")
        appendLine("######################################################################")
        appendLine()
        reconstruction?.let {
            appendLine("==== RÉSUMÉ RECONSTRUCTION MÉCANISME ====")
            appendLine("Résultat : ${it.outcome.label}")
            appendLine(it.summary)
            it.firstBreakDescription?.let { br -> appendLine("Première rupture : $br") }
            it.firstBreakProof?.let { pr -> appendLine("Preuve rupture : $pr") }
            appendLine()
        }
        appendLine("==== HAL MIS À PLAT (SPÉCIFICATION PORTAGE) ====")
        if (flattenedHals.isEmpty()) {
            appendLine("# Aucun HAL mis à plat disponible.")
        } else {
            flattenedHals.forEach { hal ->
                appendLine(hal.flattenedSourceSpec)
                appendLine()
            }
        }
        artifacts.rawDumps.forEach { (name, body) ->
            appendLine("==== SECTION BRUTE : $name ====")
            appendLine(body.ifBlank { "(vide)" })
            appendLine()
        }
    }

    private fun parseImportedSession(jsonFiles: Map<String, String>, sourceLabel: String): ImportedFodSession {
        val ovJson = JSONObject(jsonFiles["1_overview_and_mechanism.json"] ?: jsonFiles["report.json"] ?: "{}")
        val halJson = JSONObject(jsonFiles["2_hal_components_and_porting.json"] ?: "{}")
        val evtJson = JSONObject(jsonFiles["3_events_and_timeline.json"] ?: "{}")

        val dev = ovJson.optJSONObject("device_profile") ?: ovJson.optJSONObject("device") ?: JSONObject()
        val perms = ovJson.optJSONObject("permissions_and_limits") ?: ovJson.optJSONObject("permissions") ?: JSONObject()
        val rec = ovJson.optJSONObject("mechanism_reconstruction") ?: JSONObject()

        val romType = runCatching { RomType.valueOf(dev.optString("rom_type", "INDETERMINE")) }
            .getOrDefault(RomType.INDETERMINE)
        val outcome = runCatching { ReconstructionOutcome.valueOf(rec.optString("outcome", "OBSERVATION_PARTIELLE")) }
            .getOrDefault(ReconstructionOutcome.OBSERVATION_PARTIELLE)

        val flattenedHals = mutableListOf<FlattenedHalArtifact>()
        val halsArr = halJson.optJSONArray("flattened_hals") ?: JSONArray()
        for (i in 0 until halsArr.length()) {
            val o = halsArr.getJSONObject(i)
            flattenedHals.add(
                FlattenedHalArtifact(
                    id = o.optString("id", "imp-hal-$i"),
                    serviceName = o.optString("service_name"),
                    interfaceType = o.optString("interface_type"),
                    binaryPath = o.optString("binary_path"),
                    sharedLibraries = jsonArrayToStringList(o.optJSONArray("shared_libraries")),
                    dtNeededDependencies = jsonArrayToStringList(o.optJSONArray("dt_needed")),
                    exportedSymbols = jsonArrayToStringList(o.optJSONArray("exported_symbols")),
                    referencedNodes = jsonArrayToStringList(o.optJSONArray("referenced_nodes")),
                    initRcBlocks = jsonArrayToStringList(o.optJSONArray("init_rc_blocks")),
                    permissionEntries = jsonArrayToStringList(o.optJSONArray("permission_entries")),
                    selinuxContexts = jsonArrayToStringList(o.optJSONArray("selinux_contexts")),
                    vintfEntries = jsonArrayToStringList(o.optJSONArray("vintf_entries")),
                    flattenedSourceSpec = o.optString("flattened_source_spec")
                )
            )
        }

        val components = mutableListOf<DiscoveredComponent>()
        val compArr = halJson.optJSONArray("components") ?: JSONArray()
        for (i in 0 until compArr.length()) {
            val o = compArr.getJSONObject(i)
            components.add(
                DiscoveredComponent(
                    id = o.optString("id", "imp-cmp-$i"),
                    name = o.optString("name"),
                    category = runCatching { ComponentCategory.valueOf(o.optString("category")) }.getOrDefault(ComponentCategory.FRAMEWORK_CLASS),
                    layer = runCatching { MechanismLayer.valueOf(o.optString("layer")) }.getOrDefault(MechanismLayer.FRAMEWORK),
                    pathOrIdentifier = o.optString("identifier"),
                    status = o.optString("status"),
                    evidenceLevel = runCatching { EvidenceLevel.valueOf(o.optString("evidence_level")) }.getOrDefault(EvidenceLevel.FAIT_VERIFIE),
                    rawProof = o.optString("raw_proof"),
                    discoveryWave = o.optInt("discovery_wave", 1)
                )
            )
        }

        val modifications = mutableListOf<DetectedModification>()
        val modArr = halJson.optJSONArray("modifications_and_patches") ?: JSONArray()
        for (i in 0 until modArr.length()) {
            val o = modArr.getJSONObject(i)
            modifications.add(
                DetectedModification(
                    id = o.optString("id", "imp-mod-$i"),
                    type = runCatching { ModificationType.valueOf(o.optString("type")) }.getOrDefault(ModificationType.CUSTOM_PROP),
                    name = o.optString("name"),
                    target = o.optString("target"),
                    roleSummary = o.optString("role_summary"),
                    evidenceLevel = runCatching { EvidenceLevel.valueOf(o.optString("evidence_level")) }.getOrDefault(EvidenceLevel.FAIT_VERIFIE),
                    rawProof = o.optString("raw_proof")
                )
            )
        }

        val propsMap = jsonObjectToStringMap(halJson.optJSONObject("properties"))
        val sysfsMap = jsonObjectToStringMap(halJson.optJSONObject("sysfs_nodes"))
        val rcBlocks = jsonObjectToStringMap(halJson.optJSONObject("rc_files")).entries.map { "${it.key}:\n${it.value}" }
        val permEntries = jsonObjectToStringMap(halJson.optJSONObject("permission_xmls")).entries.map { "${it.key}:\n${it.value}" }

        return ImportedFodSession(
            sourceLabel = sourceLabel,
            sessionId = ovJson.optString("session_id", sourceLabel),
            manufacturerModel = "${dev.optString("manufacturer")} ${dev.optString("model")}".trim().ifBlank { "Appareil importé" },
            romType = romType,
            romLabel = dev.optString("rom_label", romType.label),
            androidRelease = dev.optString("android_release", "?"),
            sdkInt = dev.optInt("sdk_int", 0),
            rootActive = perms.optBoolean("root_available", false),
            outcome = outcome,
            outcomeLabel = rec.optString("outcome_label", outcome.label),
            mechanismSummary = rec.optString("summary", "Résumé non présent dans l'archive."),
            causalExecutionPath = jsonArrayToStringList(rec.optJSONArray("causal_execution_path")),
            firstBreakDescription = rec.optString("first_break_description").takeIf { it.isNotBlank() && it != "null" },
            firstBreakProof = rec.optString("first_break_proof").takeIf { it.isNotBlank() && it != "null" },
            flattenedHals = flattenedHals,
            components = components,
            modifications = modifications,
            properties = propsMap,
            sysfsNodes = sysfsMap,
            rcBlocks = rcBlocks,
            permissionEntries = permEntries,
            eventsCount = evtJson.optInt("events_count", 0)
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
        val srcZip = File(getFallbackFodDir(), "$sessionId.zip")
        if (srcZip.exists()) {
            srcZip.copyTo(File(getPrimarySdcardFodDir(), "$sessionId.zip"), overwrite = true)
        }
        Result.success(dst.absolutePath)
    }

    private fun jsonArrayToStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it) }
    }

    private fun jsonObjectToStringMap(obj: JSONObject?): Map<String, String> {
        if (obj == null) return emptyMap()
        val map = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.optString(k)
        }
        return map
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

    private fun flattenedHalsToJson(items: List<FlattenedHalArtifact>) = JSONArray().apply {
        items.forEach { h ->
            put(JSONObject().apply {
                put("id", h.id)
                put("service_name", h.serviceName)
                put("interface_type", h.interfaceType)
                put("binary_path", h.binaryPath)
                put("shared_libraries", JSONArray(h.sharedLibraries))
                put("dt_needed", JSONArray(h.dtNeededDependencies))
                put("exported_symbols", JSONArray(h.exportedSymbols))
                put("referenced_nodes", JSONArray(h.referencedNodes))
                put("init_rc_blocks", JSONArray(h.initRcBlocks))
                put("permission_entries", JSONArray(h.permissionEntries))
                put("selinux_contexts", JSONArray(h.selinuxContexts))
                put("vintf_entries", JSONArray(h.vintfEntries))
                put("flattened_source_spec", h.flattenedSourceSpec)
            })
        }
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
