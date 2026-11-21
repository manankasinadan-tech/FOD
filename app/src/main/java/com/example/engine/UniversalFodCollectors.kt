package com.example.engine

import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import com.example.model.BaselineSnapshot
import com.example.model.EvidenceLevel
import com.example.model.FodEvent
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

data class RawCollectorArtifacts(
    val rawDumps: Map<String, String> = emptyMap(),
    val matchedProperties: Map<String, String> = emptyMap(),
    val sysfsValues: Map<String, String> = emptyMap(),
    val discoveredServices: List<String> = emptyList(),
    val discoveredHalFiles: List<String> = emptyList(),
    val discoveredInitFiles: List<String> = emptyList(),
    val discoveredOverlays: List<String> = emptyList(),
    val discoveredModules: List<String> = emptyList(),
    val resolvedSettingsTargets: List<String> = emptyList(),
    val enrolledTemplateCount: Int? = null,
    val logcatLines: List<String> = emptyList(),
    val collectorNotes: List<String> = emptyList(),
    val halBundle: HalExtractionBundle = HalExtractionBundle()
)

object UniversalFodCollectors {

    private val eventIdGen = AtomicInteger(1)

    val fodKeywordRegex = Regex(
        "fod|udfps|side.?fps|biometric|fingerprint|enroll|goodix|gx_fpd|silead|fpc|egistec|focaltech|qsee|trustonic|tee|hbm|lhbm|dim.?layer|alpha|notify_fod|optical|ultrasonic|phh|udfps_overlay|AuthService",
        RegexOption.IGNORE_CASE
    )

    val enrollmentTriggerRegex = Regex(
        "enroll|FingerprintEnroll|BiometricEnroll|UdfpsController|onAcquired|onEnrollResult|fod_status|lhbm|hbm_mode|dimlayer|StartEnroll|enrollments|BiometricPrompt",
        RegexOption.IGNORE_CASE
    )

    val enrollmentSuccessRegex = Regex(
        "onEnrollResult.*remaining=0|enroll.*success|enrollment.?complet|added fingerprint|BiometricEnroll.*finish|template.?saved",
        RegexOption.IGNORE_CASE
    )

    val failureSignalRegex = Regex(
        "error|failed|avc:\\s*denied|binder died|hal.?died|timeout|canceled|EACCES|ENOENT|unable to|exception|crash",
        RegexOption.IGNORE_CASE
    )

    suspend fun captureBaseline(context: Context, useRoot: Boolean): Pair<BaselineSnapshot, RawCollectorArtifacts> {
        val (bioCode, bioLabel) = DeviceProfiler.inspectBiometricState(
            context,
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT)
        )
        val brightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val refreshRate = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.mode?.refreshRate ?: 0f

        val artifacts = collectEnvironmentArtifacts(context, useRoot, wave = 0)
        val snapshot = BaselineSnapshot(
            timestampIso = RootDiagnostics.isoNow(),
            biometricStatusCode = bioCode,
            biometricStatusLabel = bioLabel,
            screenBrightness = brightness,
            refreshRateHz = refreshRate,
            matchedPropertiesCount = artifacts.matchedProperties.size,
            readableNodesCount = artifacts.sysfsValues.size,
            keyProperties = artifacts.matchedProperties,
            readableSysfsSnapshot = artifacts.sysfsValues
        )
        return snapshot to artifacts
    }

    suspend fun collectEnvironmentArtifacts(
        context: Context,
        useRoot: Boolean,
        wave: Int
    ): RawCollectorArtifacts {
        val rawDumps = linkedMapOf<String, String>()
        val notes = mutableListOf<String>()

        // 1. Propriétés système liées à FOD / biométrie / affichage / ROM
        val props = linkedMapOf<String, String>()
        val propRes = ShellExecutor.runReadOnly(
            "getprop",
            useRoot = useRoot,
            timeoutMs = 3500L,
            maxLines = 1500
        )
        if (propRes.isSuccess) {
            val matchedLines = mutableListOf<String>()
            propRes.stdout.lineSequence().forEach { line ->
                val match = Regex("""^\[([^\]]+)\]: \[(.*)]$""").find(line.trim())
                if (match != null) {
                    val k = match.groupValues[1]
                    val v = match.groupValues[2]
                    if (fodKeywordRegex.containsMatchIn(k) || fodKeywordRegex.containsMatchIn(v)) {
                        props[k] = v
                        matchedLines.add(line)
                    }
                }
            }
            rawDumps["getprop_fod.txt"] = matchedLines.joinToString("\n").ifBlank { "# Aucune propriété FOD filtrée par getprop" }
        } else {
            notes.add("getprop shell limité: ${propRes.stderr}")
        }

        // 2. Cibles Paramètres Android pour l'enregistrement biométrique
        val pm = context.packageManager
        val resolvedTargets = mutableListOf<String>()
        val candidateActions = listOf(
            Settings.ACTION_BIOMETRIC_ENROLL,
            "android.settings.FINGERPRINT_ENROLL",
            Settings.ACTION_SECURITY_SETTINGS
        )
        for (action in candidateActions) {
            val activities = runCatching {
                pm.queryIntentActivities(Intent(action), 0)
            }.getOrDefault(emptyList())
            activities.forEach { info ->
                resolvedTargets.add("$action -> ${info.activityInfo.packageName}/${info.activityInfo.name}")
            }
        }

        // 3. Services, HAL & Dumpsys
        val services = mutableListOf<String>()
        val halFiles = mutableListOf<String>()
        val initFiles = mutableListOf<String>()
        val overlays = mutableListOf<String>()
        val modules = mutableListOf<String>()
        var enrolledCount: Int? = null

        val dumpsysSections = StringBuilder()
        if (useRoot) {
            val svcRes = ShellExecutor.runReadOnly("service list", useRoot = true, timeoutMs = 3500L, maxLines = 400)
            if (svcRes.isSuccess) {
                svcRes.stdout.lineSequence()
                    .filter { fodKeywordRegex.containsMatchIn(it) || it.contains("SurfaceFlinger", true) || it.contains("display", true) }
                    .forEach { services.add(it.trim()) }
            }

            val lshalRes = ShellExecutor.runReadOnly("lshal 2>/dev/null | grep -iE 'biometrics|fingerprint|display|lights|touch|goodix|silead|fpc|egistec|fod'", useRoot = true, timeoutMs = 4000L, maxLines = 250)
            if (lshalRes.isSuccess && lshalRes.stdout.isNotBlank()) {
                lshalRes.stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { services.add("HAL: $it") }
            }

            val dumpFp = ShellExecutor.runReadOnly("dumpsys fingerprint", useRoot = true, timeoutMs = 4000L, maxLines = 300)
            if (dumpFp.isSuccess && dumpFp.stdout.isNotBlank()) {
                dumpsysSections.append("=== dumpsys fingerprint ===\n").append(dumpFp.stdout).append("\n\n")
                enrolledCount = parseEnrolledCount(dumpFp.stdout)
            }

            val dumpBio = ShellExecutor.runReadOnly("dumpsys biometric", useRoot = true, timeoutMs = 4000L, maxLines = 300)
            if (dumpBio.isSuccess && dumpBio.stdout.isNotBlank()) {
                dumpsysSections.append("=== dumpsys biometric ===\n").append(dumpBio.stdout).append("\n\n")
                if (enrolledCount == null) {
                    enrolledCount = parseEnrolledCount(dumpBio.stdout)
                }
            }

            val dumpDisp = ShellExecutor.runReadOnly("dumpsys display | grep -iE 'hbm|lhbm|brightness|hdr|mode|fod|udfps|refresh' | head -n 80", useRoot = true, timeoutMs = 3500L, maxLines = 100)
            if (dumpDisp.isSuccess && dumpDisp.stdout.isNotBlank()) {
                dumpsysSections.append("=== dumpsys display (extrait FOD/HBM) ===\n").append(dumpDisp.stdout).append("\n\n")
            }

            val libsRes = ShellExecutor.runReadOnly(
                "ls -1 /vendor/lib64/hw/*finger* /vendor/lib64/hw/*fod* /vendor/lib/hw/*finger* /vendor/bin/hw/*fingerprint* /vendor/bin/hw/*biometrics* /odm/bin/hw/*finger* /vendor/lib64/*goodix* /vendor/lib64/*silead* /vendor/lib64/*fpc* /vendor/lib64/*egistec* /vendor/lib64/*fod* 2>/dev/null || true",
                useRoot = true,
                timeoutMs = 3500L,
                maxLines = 200
            )
            if (libsRes.stdout.isNotBlank()) {
                libsRes.stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.contains("No such file") }.forEach { halFiles.add(it) }
            }

            val rcRes = ShellExecutor.runReadOnly(
                "grep -rnIE 'fingerprint|biometric|fod|udfps|goodix|silead|fpc|egistec|gx_fpd' /vendor/etc/init /system/etc/init /system_ext/etc/init /odm/etc/init 2>/dev/null | head -n 120 || true",
                useRoot = true,
                timeoutMs = 4000L,
                maxLines = 150
            )
            if (rcRes.stdout.isNotBlank()) {
                rcRes.stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { initFiles.add(it) }
            }

            val ovRes = ShellExecutor.runReadOnly("cmd overlay list 2>/dev/null | grep -iE 'fod|udfps|biometric|fingerprint|systemui|settings' || true", useRoot = true, timeoutMs = 3500L, maxLines = 150)
            if (ovRes.stdout.isNotBlank()) {
                ovRes.stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { overlays.add(it) }
            }

            val modRes = ShellExecutor.runReadOnly(
                "ls -1 /data/adb/modules /data/adb/service.d /data/adb/post-fs-data.d 2>/dev/null || true",
                useRoot = true,
                timeoutMs = 3000L,
                maxLines = 120
            )
            if (modRes.stdout.isNotBlank()) {
                modRes.stdout.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.forEach { modules.add(it) }
            }
        } else {
            notes.add("Collecte HAL/dumpsys/init/modules restreinte (mode sans ROOT : utilisation des API Android et nœuds accessibles).")
            val vintfPaths = listOf("/vendor/etc/vintf/manifest.xml", "/system/etc/vintf/manifest.xml", "/odm/etc/vintf/manifest.xml")
            for (vp in vintfPaths) {
                runCatching {
                    val f = File(vp)
                    if (f.exists() && f.canRead()) {
                        val matches = f.readLines()
                            .filter { fodKeywordRegex.containsMatchIn(it) || it.contains("biometrics", true) }
                            .take(40)
                        if (matches.isNotEmpty()) {
                            services.addAll(matches.map { "VINTF($vp): ${it.trim()}" })
                        }
                    }
                }
            }
        }

        // 4. Découverte dynamique de nœuds sysfs / proc liés à FOD, HBM, tactile, affichage
        val sysfsValues = discoverSysfsAndProcNodes(useRoot)

        // 5. Extraction et mise à plat complète des HAL, .rc, permissions XML, VINTF et ELF64
        val halBundle = ElfAndHalInspector.extractAndFlattenHals(
            useRoot = useRoot,
            discoveredServices = services.distinct(),
            discoveredHalFiles = halFiles.distinct(),
            discoveredInitLines = initFiles.distinct()
        )

        // 6. Journaux logcat bornés (vague 0 ou vague approfondie)
        val logCmd = if (useRoot) {
            "logcat -b main,system,events,crash -d -t ${if (wave > 0) 350 else 180}"
        } else {
            "logcat -d -t 150"
        }
        val logRes = ShellExecutor.runReadOnly(logCmd, useRoot = useRoot, timeoutMs = 4000L, maxLines = 400)
        val logLines = if (logRes.isSuccess) {
            logRes.stdout.lineSequence()
                .filter { fodKeywordRegex.containsMatchIn(it) || enrollmentTriggerRegex.containsMatchIn(it) || failureSignalRegex.containsMatchIn(it) }
                .take(220)
                .toList()
        } else {
            emptyList()
        }

        rawDumps["hal_and_services.txt"] = buildString {
            appendLine("=== Services & HAL ===")
            services.forEach { appendLine(it) }
            appendLine("\n=== Bibliothèques & Binaires HAL ===")
            halFiles.forEach { appendLine(it) }
            appendLine("\n=== Scripts Init (.rc) ===")
            initFiles.forEach { appendLine(it) }
        }
        rawDumps["dumpsys_biometric.txt"] = dumpsysSections.toString().ifBlank { "# Indisponible sans ROOT ou sortie vide" }
        rawDumps["overlays_and_modules.txt"] = buildString {
            appendLine("=== Overlays RRO ===")
            overlays.forEach { appendLine(it) }
            appendLine("\n=== Modules / Scripts (/data/adb) ===")
            modules.forEach { appendLine(it) }
        }
        rawDumps["sysfs_snapshot.txt"] = sysfsValues.entries.joinToString("\n") { "${it.key} = ${it.value}" }
            .ifBlank { "# Aucun nœud sysfs/proc FOD lisible avec les droits actuels" }
        rawDumps["logcat_fod.txt"] = logLines.joinToString("\n").ifBlank { "# Aucune ligne logcat FOD filtrée dans la fenêtre" }
        rawDumps["hal_flattened_specs.txt"] = halBundle.flattenedHals.joinToString("\n\n") { it.flattenedSourceSpec }
            .ifBlank { "# Aucune fiche HAL mise à plat générée" }
        rawDumps["rc_and_permissions.txt"] = buildString {
            appendLine("=== CONTENUS COMPLETS DES FICHIERS INIT (.rc) ===")
            halBundle.rcFileContents.forEach { (p, txt) ->
                appendLine("--- $p ---")
                appendLine(txt)
                appendLine()
            }
            appendLine("=== PERMISSIONS XML BIOMÉTRIQUES / FOD ===")
            halBundle.permissionXmlContents.forEach { (p, txt) ->
                appendLine("--- $p ---")
                appendLine(txt)
                appendLine()
            }
            appendLine("=== MANIFESTS VINTF ===")
            halBundle.vintfManifestContents.forEach { (p, txt) ->
                appendLine("--- $p ---")
                appendLine(txt)
                appendLine()
            }
            appendLine("=== CONTEXTES SELINUX ===")
            halBundle.selinuxContextLines.forEach { appendLine(it) }
        }

        return RawCollectorArtifacts(
            rawDumps = rawDumps,
            matchedProperties = props,
            sysfsValues = sysfsValues,
            discoveredServices = services.distinct(),
            discoveredHalFiles = halFiles.distinct(),
            discoveredInitFiles = initFiles.distinct(),
            discoveredOverlays = overlays.distinct(),
            discoveredModules = modules.distinct(),
            resolvedSettingsTargets = resolvedTargets.distinct(),
            enrolledTemplateCount = enrolledCount,
            logcatLines = logLines,
            collectorNotes = notes,
            halBundle = halBundle
        )
    }

    private suspend fun discoverSysfsAndProcNodes(useRoot: Boolean): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val findCmd = if (useRoot) {
            "find /sys/class/drm /sys/class/backlight /sys/class/leds /sys/devices/platform /sys/devices/virtual /sys/kernel /proc -maxdepth 3 2>/dev/null | grep -iE 'fod|finger|udfps|hbm|lhbm|dim|alpha|notify|optical|tp_|touch' | head -n 60"
        } else {
            "find /sys/class/drm /sys/class/backlight /sys/class/leds /sys/devices/virtual -maxdepth 2 2>/dev/null | grep -iE 'fod|finger|udfps|hbm|lhbm|dim|alpha|notify|brightness' | head -n 30"
        }

        val findRes = ShellExecutor.runReadOnly(findCmd, useRoot = useRoot, timeoutMs = 4000L, maxLines = 80)
        val candidateNodes = findRes.stdout.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("/sys/") || it.startsWith("/proc/") }
            .distinct()
            .take(35)
            .toList()

        for (node in candidateNodes) {
            val f = File(node)
            if (f.isDirectory) continue
            if (!useRoot) {
                val content = runCatching {
                    if (f.exists() && f.canRead()) f.readText().trim().take(120) else null
                }.getOrNull()
                if (!content.isNullOrBlank()) {
                    result[node] = content
                }
            } else {
                val readRes = ShellExecutor.runReadOnly(
                    "head -c 120 '$node' 2>/dev/null || true",
                    useRoot = true,
                    timeoutMs = 1500L,
                    maxLines = 4
                )
                val v = readRes.stdout.replace("\n", " ").trim()
                if (v.isNotBlank() && !v.contains("Permission denied", true)) {
                    result[node] = v
                } else {
                    result[node] = "(nœud détecté, lecture restreinte ou écriture seule)"
                }
            }
        }
        return result
    }

    suspend fun pollIncrementalDeltas(
        context: Context,
        useRoot: Boolean,
        previousBioCode: Int,
        previousBrightness: Int,
        previousRefreshHz: Float,
        previousSysfs: Map<String, String>,
        previousEnrolledCount: Int?,
        seenLogHashes: MutableSet<Int>
    ): PollDeltaResult {
        val newEvents = mutableListOf<FodEvent>()
        val nowMs = System.currentTimeMillis()
        val nowIso = RootDiagnostics.isoNow()

        // 1. Variation état biométrique SDK
        val hasFp = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_FINGERPRINT)
        val (curBioCode, curBioLabel) = DeviceProfiler.inspectBiometricState(context, hasFp)
        var enrollmentCompletedByBioDelta = false
        if (curBioCode != previousBioCode && previousBioCode != -1) {
            val isSuccessDelta = curBioCode == 0 && previousBioCode == 11 // BIOMETRIC_ERROR_NONE_ENROLLED -> BIOMETRIC_SUCCESS
            if (isSuccessDelta) enrollmentCompletedByBioDelta = true
            newEvents.add(
                FodEvent(
                    id = "evt-${eventIdGen.getAndIncrement()}",
                    timestampMs = nowMs,
                    timestampIso = nowIso,
                    source = "BiometricManager",
                    category = if (isSuccessDelta) "ENROLL_RESULT" else "BIOMETRIC_STATE",
                    title = if (isSuccessDelta) {
                        "Enregistrement d'empreinte confirmé (transition vers BIOMETRIC_SUCCESS)"
                    } else {
                        "Transition d'état biométrique ($previousBioCode -> $curBioCode)"
                    },
                    rawPayload = curBioLabel,
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    triggersInvestigation = true,
                    matchedSignals = listOf("BiometricManager.canAuthenticate")
                )
            )
        }

        // 2. Variation luminosité / fréquence affichage (corrélation HBM/LHBM fréquente sur FOD optique)
        val curBrightness = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        }.getOrDefault(-1)
        if (curBrightness != -1 && previousBrightness != -1 && kotlin.math.abs(curBrightness - previousBrightness) >= 20) {
            newEvents.add(
                FodEvent(
                    id = "evt-${eventIdGen.getAndIncrement()}",
                    timestampMs = nowMs,
                    timestampIso = nowIso,
                    source = "DisplaySettings",
                    category = "DISPLAY_DELTA",
                    title = "Variation importante de luminosité écran ($previousBrightness -> $curBrightness)",
                    rawPayload = "Settings.System.SCREEN_BRIGHTNESS=$curBrightness",
                    evidenceLevel = EvidenceLevel.CORRELATION_OBSERVEE,
                    triggersInvestigation = false,
                    matchedSignals = listOf("SCREEN_BRIGHTNESS")
                )
            )
        }

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val curRefresh = dm?.getDisplay(Display.DEFAULT_DISPLAY)?.mode?.refreshRate ?: 0f
        if (previousRefreshHz > 0f && curRefresh > 0f && kotlin.math.abs(curRefresh - previousRefreshHz) >= 5f) {
            newEvents.add(
                FodEvent(
                    id = "evt-${eventIdGen.getAndIncrement()}",
                    timestampMs = nowMs,
                    timestampIso = nowIso,
                    source = "DisplayManager",
                    category = "DISPLAY_DELTA",
                    title = "Changement de taux de rafraîchissement (${previousRefreshHz.toInt()} Hz -> ${curRefresh.toInt()} Hz)",
                    rawPayload = "Display.Mode.refreshRate=$curRefresh",
                    evidenceLevel = EvidenceLevel.CORRELATION_OBSERVEE,
                    triggersInvestigation = false,
                    matchedSignals = listOf("refreshRate")
                )
            )
        }

        // 3. Delta Sysfs sur les nœuds découverts
        val updatedSysfs = previousSysfs.toMutableMap()
        if (previousSysfs.isNotEmpty()) {
            for ((node, oldVal) in previousSysfs.entries.take(15)) {
                if (oldVal.startsWith("(")) continue
                val newVal = if (useRoot) {
                    val r = ShellExecutor.runReadOnly("head -c 120 '$node' 2>/dev/null || true", useRoot = true, timeoutMs = 1200L, maxLines = 2)
                    r.stdout.replace("\n", " ").trim()
                } else {
                    runCatching { File(node).takeIf { it.canRead() }?.readText()?.trim()?.take(120) }.getOrNull().orEmpty()
                }
                if (newVal.isNotBlank() && newVal != oldVal) {
                    updatedSysfs[node] = newVal
                    newEvents.add(
                        FodEvent(
                            id = "evt-${eventIdGen.getAndIncrement()}",
                            timestampMs = nowMs,
                            timestampIso = nowIso,
                            source = "SysfsWatch",
                            category = "KERNEL_NODE_DELTA",
                            title = "Modification nœud kernel : ${node.substringAfterLast('/')}",
                            rawPayload = "$node: '$oldVal' -> '$newVal'",
                            evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                            triggersInvestigation = true,
                            matchedSignals = listOf(node)
                        )
                    )
                }
            }
        }

        // 4. Flux logcat temps réel (ROOT: buffers système/main/events, Non-ROOT: logcat local)
        val logCmd = if (useRoot) "logcat -b main,system,events -d -t 120" else "logcat -d -t 60"
        val logRes = ShellExecutor.runReadOnly(logCmd, useRoot = useRoot, timeoutMs = 2500L, maxLines = 150)
        var logTrigger = false
        var logSuccess = false
        var logFailure: String? = null
        if (logRes.isSuccess && logRes.stdout.isNotBlank()) {
            logRes.stdout.lineSequence().forEach { line ->
                if (!fodKeywordRegex.containsMatchIn(line) && !enrollmentTriggerRegex.containsMatchIn(line)) return@forEach
                val h = line.hashCode()
                if (!seenLogHashes.add(h)) return@forEach
                if (seenLogHashes.size > 4000) seenLogHashes.clear()

                val isEnroll = enrollmentTriggerRegex.containsMatchIn(line)
                val isSuccess = enrollmentSuccessRegex.containsMatchIn(line)
                val isFail = failureSignalRegex.containsMatchIn(line)

                if (isEnroll) logTrigger = true
                if (isSuccess) logSuccess = true
                if (isFail && logFailure == null) logFailure = line

                newEvents.add(
                    FodEvent(
                        id = "evt-${eventIdGen.getAndIncrement()}",
                        timestampMs = nowMs,
                        timestampIso = nowIso,
                        source = if (useRoot) "Logcat(System)" else "Logcat(App)",
                        category = when {
                            isSuccess -> "ENROLL_RESULT"
                            isFail -> "ERROR_SIGNAL"
                            isEnroll -> "ENROLL_TRIGGER"
                            else -> "FOD_ACTIVITY"
                        },
                        title = when {
                            isSuccess -> "Signal de succès d'enregistrement détecté dans logcat"
                            isFail -> "Signal d'erreur / refus détecté dans les journaux"
                            isEnroll -> "Événement lié à l'enregistrement d'empreinte détecté"
                            else -> "Activité FOD/Biométrique observée"
                        },
                        rawPayload = line.take(260),
                        evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                        triggersInvestigation = isEnroll || isSuccess || isFail,
                        matchedSignals = fodKeywordRegex.findAll(line).map { it.value }.distinct().toList()
                    )
                )
            }
        }

        // 5. Delta du nombre d'empreintes dans dumpsys fingerprint (ROOT uniquement)
        var curEnrolledCount = previousEnrolledCount
        if (useRoot) {
            val fpRes = ShellExecutor.runReadOnly("dumpsys fingerprint | grep -iE 'finger|enrolled|template' | head -n 40", useRoot = true, timeoutMs = 2000L, maxLines = 45)
            if (fpRes.isSuccess && fpRes.stdout.isNotBlank()) {
                val count = parseEnrolledCount(fpRes.stdout)
                if (count != null && previousEnrolledCount != null && count > previousEnrolledCount) {
                    enrollmentCompletedByBioDelta = true
                    newEvents.add(
                        FodEvent(
                            id = "evt-${eventIdGen.getAndIncrement()}",
                            timestampMs = nowMs,
                            timestampIso = nowIso,
                            source = "DumpsysFingerprint",
                            category = "ENROLL_RESULT",
                            title = "Nouveau modèle d'empreinte comptabilisé ($previousEnrolledCount -> $count)",
                            rawPayload = fpRes.stdout.take(240),
                            evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                            triggersInvestigation = true,
                            matchedSignals = listOf("dumpsys fingerprint")
                        )
                    )
                }
                if (count != null) curEnrolledCount = count
            }
        }

        return PollDeltaResult(
            newEvents = newEvents,
            currentBioCode = curBioCode,
            currentBrightness = curBrightness,
            currentRefreshHz = curRefresh,
            updatedSysfs = updatedSysfs,
            currentEnrolledCount = curEnrolledCount,
            investigationTriggered = logTrigger || newEvents.any { it.triggersInvestigation },
            enrollmentSucceeded = enrollmentCompletedByBioDelta || logSuccess,
            failureLine = logFailure
        )
    }

    fun createSyntheticAppEvent(
        source: String,
        category: String,
        title: String,
        rawPayload: String,
        evidenceLevel: EvidenceLevel = EvidenceLevel.FAIT_VERIFIE,
        triggersInvestigation: Boolean = false
    ): FodEvent {
        return FodEvent(
            id = "evt-${eventIdGen.getAndIncrement()}",
            timestampMs = System.currentTimeMillis(),
            timestampIso = RootDiagnostics.isoNow(),
            source = source,
            category = category,
            title = title,
            rawPayload = rawPayload,
            evidenceLevel = evidenceLevel,
            triggersInvestigation = triggersInvestigation
        )
    }

    private fun parseEnrolledCount(dumpsysOutput: String): Int? {
        val countRegex = Regex("enrolled[^0-9]*(\\d+)|fingerId=|Finger\\s*#?\\d+", RegexOption.IGNORE_CASE)
        val directMatch = Regex("enrolled(?:\\s+fingerprints)?\\s*[:=]\\s*(\\d+)", RegexOption.IGNORE_CASE).find(dumpsysOutput)
        if (directMatch != null) {
            return directMatch.groupValues[1].toIntOrNull()
        }
        val fingerIds = Regex("fingerId\\s*[=:]\\s*-?\\d+", RegexOption.IGNORE_CASE).findAll(dumpsysOutput).count()
        if (fingerIds > 0) return fingerIds
        if (countRegex.containsMatchIn(dumpsysOutput)) return null
        return null
    }
}

data class PollDeltaResult(
    val newEvents: List<FodEvent>,
    val currentBioCode: Int,
    val currentBrightness: Int,
    val currentRefreshHz: Float,
    val updatedSysfs: Map<String, String>,
    val currentEnrolledCount: Int?,
    val investigationTriggered: Boolean,
    val enrollmentSucceeded: Boolean,
    val failureLine: String?
)
