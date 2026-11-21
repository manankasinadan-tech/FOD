package com.example.engine

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import androidx.core.content.ContextCompat
import com.example.model.DeviceProfile
import com.example.model.RomType

object DeviceProfiler {

    suspend fun collectProfile(context: Context, useRoot: Boolean): DeviceProfile {
        val props = readKeyBuildProperties(useRoot)

        val romEvidence = mutableListOf<String>()
        val romType = classifyRom(props, romEvidence)

        val pm = context.packageManager
        val hasFpFeature = pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)

        val (bioCode, bioLabel) = inspectBiometricState(context, hasFpFeature)

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val defaultDisplay = dm?.getDisplay(Display.DEFAULT_DISPLAY)
        val resolution = if (defaultDisplay != null) {
            val mode = defaultDisplay.mode
            "${mode.physicalWidth}x${mode.physicalHeight}"
        } else {
            "Inconnue"
        }
        val refreshRate = defaultDisplay?.mode?.refreshRate ?: 0f
        val hdrTypes = if (defaultDisplay != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            defaultDisplay.hdrCapabilities?.supportedHdrTypes?.map { hdrTypeLabel(it) } ?: emptyList()
        } else {
            emptyList()
        }

        val selinuxRes = ShellExecutor.runReadOnly("getenforce", useRoot = useRoot, timeoutMs = 2000L, maxLines = 4)
        val selinuxMode = selinuxRes.stdout.lines().firstOrNull()?.takeIf { it.isNotBlank() } ?: "Restreint / Inconnu"

        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it != Build.UNKNOWN } ?: (props["ro.board.platform"] ?: Build.BOARD)
        } else {
            props["ro.board.platform"] ?: Build.BOARD
        }

        return DeviceProfile(
            brand = Build.BRAND,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            board = Build.BOARD,
            hardware = Build.HARDWARE,
            socModel = socModel,
            abis = Build.SUPPORTED_ABIS.toList(),
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Build.VERSION.SECURITY_PATCH else "Inconnu",
            buildFingerprint = Build.FINGERPRINT,
            buildType = Build.TYPE,
            buildTags = Build.TAGS,
            romType = romType,
            romEvidence = romEvidence,
            selinuxMode = selinuxMode,
            hasFingerprintFeature = hasFpFeature,
            biometricStatusLabel = bioLabel,
            biometricStatusCode = bioCode,
            displayResolution = resolution,
            displayRefreshRateHz = refreshRate,
            displayHdrTypes = hdrTypes
        )
    }

    fun inspectBiometricState(context: Context, hasFpFeature: Boolean): Pair<Int, String> {
        if (!hasFpFeature) {
            return -10 to "Fonctionnalité android.hardware.fingerprint absente"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val bm = context.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
            if (bm != null) {
                val status = bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
                val label = when (status) {
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS ->
                        "BIOMETRIC_SUCCESS (capteur prêt et empreinte(s) enregistrée(s))"
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                        "BIOMETRIC_ERROR_NONE_ENROLLED (matériel prêt, aucune empreinte enregistrée)"
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                        "BIOMETRIC_ERROR_NO_HARDWARE"
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
                        "BIOMETRIC_ERROR_HW_UNAVAILABLE (matériel temporairement indisponible)"
                    android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
                        "BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED"
                    else -> "Code biométrique: $status"
                }
                return status to label
            }
        }
        @Suppress("DEPRECATION")
        val fm = ContextCompat.getSystemService(context, android.hardware.fingerprint.FingerprintManager::class.java)
        return if (fm != null) {
            val hw = runCatching { fm.isHardwareDetected }.getOrDefault(false)
            val enrolled = runCatching { fm.hasEnrolledFingerprints() }.getOrDefault(false)
            when {
                !hw -> 12 to "FingerprintManager: matériel non détecté"
                enrolled -> 0 to "FingerprintManager: matériel prêt et empreinte(s) enregistrée(s)"
                else -> 11 to "FingerprintManager: matériel prêt, aucune empreinte enregistrée"
            }
        } else {
            -1 to "Service biométrique non accessible via API SDK"
        }
    }

    private suspend fun readKeyBuildProperties(useRoot: Boolean): Map<String, String> {
        val keys = listOf(
            "ro.treble.enabled",
            "ro.vndk.version",
            "ro.product.gsi.version",
            "ro.system.build.fingerprint",
            "ro.vendor.build.fingerprint",
            "ro.build.flavor",
            "ro.build.display.id",
            "ro.lineage.version",
            "ro.crdroid.version",
            "ro.evolution.version",
            "ro.pixelexperience.version",
            "ro.custom.build.version",
            "ro.modversion",
            "ro.board.platform",
            "persist.sys.phh.fod.type",
            "ro.hardware.fingerprint"
        )
        val map = linkedMapOf<String, String>()
        for (k in keys) {
            val v = getSystemPropertyReflection(k)
            if (!v.isNullOrBlank()) {
                map[k] = v
            }
        }
        val res = ShellExecutor.runReadOnly(
            "getprop | grep -iE 'treble|gsi|lineage|crdroid|evolution|phh|vndk|flavor|custom|modversion|fod|fingerprint'",
            useRoot = useRoot,
            timeoutMs = 3000L,
            maxLines = 200
        )
        if (res.isSuccess && res.stdout.isNotBlank()) {
            res.stdout.lineSequence().forEach { line ->
                val match = Regex("""^\[([^\]]+)\]: \[(.*)]$""").find(line.trim())
                if (match != null) {
                    map[match.groupValues[1]] = match.groupValues[2]
                }
            }
        }
        return map
    }

    fun getSystemPropertyReflection(key: String): String? {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
            (getMethod.invoke(null, key, "") as? String)?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private fun classifyRom(props: Map<String, String>, evidence: MutableList<String>): RomType {
        val sysFp = props["ro.system.build.fingerprint"].orEmpty()
        val venFp = props["ro.vendor.build.fingerprint"].orEmpty()
        val flavor = props["ro.build.flavor"].orEmpty()
        val displayId = props["ro.build.display.id"].orEmpty()

        val hasPhh = props.keys.any { it.contains("phh", ignoreCase = true) }
        val hasGsiProp = props.keys.any { it.contains("gsi", ignoreCase = true) } ||
            flavor.contains("treble", ignoreCase = true) ||
            flavor.contains("aosp_", ignoreCase = true) ||
            sysFp.contains("aosp", ignoreCase = true) && venFp.isNotBlank() && !sysFp.startsWith(venFp.substringBefore("/"))

        if (hasPhh || hasGsiProp) {
            if (hasPhh) evidence.add("Propriétés PHH/Treble détectées (${props.keys.filter { it.contains("phh", true) }.take(3).joinToString()})")
            if (flavor.isNotBlank()) evidence.add("ro.build.flavor = $flavor")
            if (sysFp.isNotBlank() && venFp.isNotBlank()) evidence.add("Empreinte système ($sysFp) distincte du vendor ($venFp)")
            return RomType.GSI_TREBLE
        }

        val customKeys = props.keys.filter { k ->
            listOf("lineage", "crdroid", "evolution", "pixelexperience", "modversion", "custom", "aospa", "rising", "derp")
                .any { k.contains(it, ignoreCase = true) }
        }
        if (customKeys.isNotEmpty() || Build.TAGS.contains("test-keys") || displayId.contains("Lineage", ignoreCase = true)) {
            customKeys.take(3).forEach { evidence.add("$it = ${props[it]}") }
            if (Build.TAGS.contains("test-keys")) evidence.add("ro.build.tags = ${Build.TAGS}")
            return RomType.CUSTOM_ROM
        }

        if (Build.TAGS.contains("release-keys") && Build.BRAND.isNotBlank()) {
            evidence.add("Signature release-keys alignée sur constructeur ${Build.MANUFACTURER} (${Build.BRAND})")
            if (sysFp.isNotBlank()) evidence.add("ro.system.build.fingerprint = $sysFp")
            return RomType.STOCK_OEM
        }

        evidence.add("Build type=${Build.TYPE}, tags=${Build.TAGS}")
        return RomType.INDETERMINE
    }

    private fun hdrTypeLabel(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "Dolby Vision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "HDR10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "HLG"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "HDR10+"
        else -> "HDR($type)"
    }
}
