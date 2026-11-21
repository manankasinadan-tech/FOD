package com.example

import com.example.engine.CausalReconstructionEngine
import com.example.engine.RawCollectorArtifacts
import com.example.engine.ShellExecutor
import com.example.model.CapabilityLimitation
import com.example.model.DeviceProfile
import com.example.model.EvidenceLevel
import com.example.model.FodEvent
import com.example.model.ModificationType
import com.example.model.PermissionsState
import com.example.model.ReconstructionOutcome
import com.example.model.RomType
import com.example.model.RootDiagnosticResult
import com.example.model.StageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FodEngineUnitTest {

    @Test
    fun shellExecutor_allowsReadOnlyCommandsAndBlocksMutations() {
        assertNull(ShellExecutor.isCommandStrictlyReadOnly("getprop"))
        assertNull(ShellExecutor.isCommandStrictlyReadOnly("dumpsys fingerprint"))
        assertNull(ShellExecutor.isCommandStrictlyReadOnly("logcat -b main,system -d -t 120"))
        assertNull(ShellExecutor.isCommandStrictlyReadOnly("head -c 120 '/sys/class/drm/card0-DSI-1/hbm'"))

        assertNotNull(ShellExecutor.isCommandStrictlyReadOnly("setprop persist.sys.fod 1"))
        assertNotNull(ShellExecutor.isCommandStrictlyReadOnly("rm -rf /data/local/tmp/x"))
        assertNotNull(ShellExecutor.isCommandStrictlyReadOnly("mount -o rw,remount /vendor"))
        assertNotNull(ShellExecutor.isCommandStrictlyReadOnly("echo 1 > /sys/class/drm/hbm"))
    }

    @Test
    fun causalEngine_reconstructsGsiSuccessWithoutInventingMissingLayers() {
        val profile = DeviceProfile(
            brand = "Generic",
            manufacturer = "OEM",
            model = "DeviceX",
            androidRelease = "15",
            sdkInt = 35,
            romType = RomType.GSI_TREBLE,
            romEvidence = listOf("Propriétés PHH/Treble détectées")
        )
        val perms = PermissionsState(
            storagePermissionGranted = true,
            sdcardFodWritable = true,
            rootEnabledByUser = true,
            rootDiagnostic = RootDiagnosticResult(isRootAvailable = true, detectedManager = "Magisk")
        )
        val artifacts = RawCollectorArtifacts(
            matchedProperties = mapOf("persist.sys.phh.fod.type" to "goodix"),
            sysfsValues = mapOf("/sys/class/drm/card0-DSI-1/fod_hbm" to "1"),
            discoveredServices = listOf("HAL: vendor.goodix.hardware.biometrics.fingerprint@2.1::IGoodixFingerprintDaemon/default"),
            resolvedSettingsTargets = listOf("android.settings.BIOMETRIC_ENROLL -> com.android.settings/.biometrics.BiometricEnrollIntroduction")
        )
        val events = listOf(
            FodEvent(
                id = "evt-1",
                timestampMs = 1000L,
                timestampIso = "2026-11-20T16:00:00Z",
                source = "BiometricManager",
                category = "ENROLL_RESULT",
                title = "Enregistrement confirmé",
                rawPayload = "BIOMETRIC_SUCCESS",
                evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                triggersInvestigation = true
            )
        )

        val synthesis = CausalReconstructionEngine.synthesize(
            profile = profile,
            permissions = perms,
            artifacts = artifacts,
            events = events,
            enrollmentSucceeded = true,
            failureEvidence = null
        )

        assertEquals(ReconstructionOutcome.ENROLEMENT_REUSSI, synthesis.reconstruction.outcome)
        assertTrue(synthesis.modifications.any { it.type == ModificationType.GSI_SHIM })
        assertTrue(synthesis.components.any { it.rawProof.contains("goodix", ignoreCase = true) })
        assertNull(synthesis.reconstruction.firstBreakDescription)
    }

    @Test
    fun causalEngine_identifiesFirstBreakAndRespectsNonRootLimitations() {
        val profile = DeviceProfile(romType = RomType.CUSTOM_ROM)
        val perms = PermissionsState(
            storagePermissionGranted = false,
            sdcardFodWritable = false,
            rootEnabledByUser = false,
            limitations = listOf(
                CapabilityLimitation(
                    code = "ROOT_DISABLED",
                    title = "Privilèges ROOT désactivés",
                    impact = "Flux kernel restreint",
                    requiresRoot = true
                )
            )
        )
        val events = listOf(
            FodEvent(
                id = "evt-err",
                timestampMs = 2000L,
                timestampIso = "2026-11-20T16:01:00Z",
                source = "Logcat(App)",
                category = "ERROR_SIGNAL",
                title = "Erreur HAL détectée",
                rawPayload = "E FingerprintService: binder died on vendor.silead.hardware.fingerprint",
                evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                triggersInvestigation = true
            )
        )

        val synthesis = CausalReconstructionEngine.synthesize(
            profile = profile,
            permissions = perms,
            artifacts = RawCollectorArtifacts(),
            events = events,
            enrollmentSucceeded = false,
            failureEvidence = null
        )

        assertEquals(ReconstructionOutcome.ECHEC_DETECTE, synthesis.reconstruction.outcome)
        assertNotNull(synthesis.reconstruction.firstBreakDescription)
        assertTrue(synthesis.reconstruction.firstBreakProof?.contains("binder died") == true)
        assertTrue(synthesis.reconstruction.stages.any { it.status == StageStatus.FIRST_BREAK })
        assertTrue(synthesis.reconstruction.stages.any { it.status == StageStatus.UNOBSERVED_LIMITED })
    }
}
