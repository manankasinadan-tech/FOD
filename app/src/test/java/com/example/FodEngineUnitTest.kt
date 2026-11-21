package com.example

import com.example.engine.CausalReconstructionEngine
import com.example.engine.ElfAndHalInspector
import com.example.engine.FodComparatorEngine
import com.example.engine.RawCollectorArtifacts
import com.example.engine.ShellExecutor
import com.example.model.CapabilityLimitation
import com.example.model.ComponentCategory
import com.example.model.DeviceProfile
import com.example.model.DiffSeverity
import com.example.model.DiscoveredComponent
import com.example.model.EvidenceLevel
import com.example.model.FlattenedHalArtifact
import com.example.model.FodEvent
import com.example.model.ImportedFodSession
import com.example.model.MechanismLayer
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    @Test
    fun elfInspector_parsesElf64DtNeededAndExportedSymbols() {
        // Construction d'un binaire ELF64 minimal valide avec .dynstr, .dynamic (DT_NEEDED) et .dynsym
        val dynstrBytes = "\u0000libvendor_fod_shim.so\u0000fod_hal_init\u0000goodix_enroll\u0000".toByteArray(Charsets.US_ASCII)
        val libOffset = 1
        val sym1Offset = 1 + "libvendor_fod_shim.so\u0000".length
        val sym2Offset = sym1Offset + "fod_hal_init\u0000".length

        val dynamicOffset = 64
        val dynamicSize = 32 // DT_NEEDED (16B) + DT_NULL (16B)
        val dynsymOffset = dynamicOffset + dynamicSize
        val dynsymSize = 48 // 2 entrées Elf64_Sym de 24 octets
        val dynstrOffset = dynsymOffset + dynsymSize
        val shoff = dynstrOffset + dynstrBytes.size
        val shentsize = 64
        val shnum = 4 // NULL, .dynstr (idx 1), .dynamic (idx 2), .dynsym (idx 3)

        val totalSize = shoff + shnum * shentsize
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // En-tête ELF64
        buf.put(0, 0x7F.toByte())
        buf.put(1, 'E'.code.toByte())
        buf.put(2, 'L'.code.toByte())
        buf.put(3, 'F'.code.toByte())
        buf.put(4, 2.toByte()) // EI_CLASS = ELFCLASS64
        buf.put(5, 1.toByte()) // EI_DATA = ELFDATA2LSB
        buf.putLong(40, shoff.toLong())
        buf.putShort(58, shentsize.toShort())
        buf.putShort(60, shnum.toShort())
        buf.putShort(62, 0.toShort())

        // .dynamic : DT_NEEDED = 1 -> libOffset, puis DT_NULL = 0
        buf.putLong(dynamicOffset, 1L)
        buf.putLong(dynamicOffset + 8, libOffset.toLong())
        buf.putLong(dynamicOffset + 16, 0L)
        buf.putLong(dynamicOffset + 24, 0L)

        // .dynsym : 2 symboles globaux définis (bind = STB_GLOBAL = 1 => st_info = 0x12, st_shndx = 1)
        buf.putInt(dynsymOffset, sym1Offset)
        buf.put(dynsymOffset + 4, 0x12.toByte())
        buf.putShort(dynsymOffset + 6, 1.toShort())

        buf.putInt(dynsymOffset + 24, sym2Offset)
        buf.put(dynsymOffset + 24 + 4, 0x12.toByte())
        buf.putShort(dynsymOffset + 24 + 6, 1.toShort())

        // .dynstr
        System.arraycopy(dynstrBytes, 0, buf.array(), dynstrOffset, dynstrBytes.size)

        // Section 1: .dynstr (SHT_STRTAB = 3)
        val sh1 = shoff + 1 * shentsize
        buf.putInt(sh1 + 4, 3)
        buf.putLong(sh1 + 24, dynstrOffset.toLong())
        buf.putLong(sh1 + 32, dynstrBytes.size.toLong())

        // Section 2: .dynamic (SHT_DYNAMIC = 6, sh_link = 1)
        val sh2 = shoff + 2 * shentsize
        buf.putInt(sh2 + 4, 6)
        buf.putLong(sh2 + 24, dynamicOffset.toLong())
        buf.putLong(sh2 + 32, dynamicSize.toLong())
        buf.putInt(sh2 + 40, 1)

        // Section 3: .dynsym (SHT_DYNSYM = 11, sh_link = 1, sh_entsize = 24)
        val sh3 = shoff + 3 * shentsize
        buf.putInt(sh3 + 4, 11)
        buf.putLong(sh3 + 24, dynsymOffset.toLong())
        buf.putLong(sh3 + 32, dynsymSize.toLong())
        buf.putInt(sh3 + 40, 1)
        buf.putLong(sh3 + 56, 24L)

        val result = ElfAndHalInspector.parseElf64ReadOnly(buf.array())
        assertTrue(result.isValidElf64)
        assertTrue(result.dtNeeded.contains("libvendor_fod_shim.so"))
        assertTrue(result.exportedSymbols.contains("fod_hal_init"))
        assertTrue(result.exportedSymbols.contains("goodix_enroll"))
    }

    @Test
    fun comparatorEngine_identifiesMissingGsiComponentsAndPortingChecklist() {
        val stockSession = ImportedFodSession(
            sourceLabel = "stock_working.zip",
            sessionId = "session_stock",
            manufacturerModel = "OEM Phone",
            romType = RomType.STOCK_OEM,
            romLabel = RomType.STOCK_OEM.label,
            androidRelease = "15",
            sdkInt = 35,
            rootActive = true,
            outcome = ReconstructionOutcome.ENROLEMENT_REUSSI,
            outcomeLabel = ReconstructionOutcome.ENROLEMENT_REUSSI.label,
            mechanismSummary = "Enrôlement fonctionnel via HAL Goodix + HBM sysfs.",
            causalExecutionPath = listOf("Settings -> Goodix HAL -> DRM HBM"),
            firstBreakDescription = null,
            firstBreakProof = null,
            flattenedHals = listOf(
                FlattenedHalArtifact(
                    id = "hal-1",
                    serviceName = "vendor.goodix.hardware.biometrics.fingerprint@2.1::IGoodixFingerprintDaemon/default",
                    interfaceType = "HIDL",
                    binaryPath = "/vendor/bin/hw/android.hardware.biometrics.fingerprint@2.1-service",
                    sharedLibraries = listOf("/vendor/lib64/libgf_hal.so"),
                    dtNeededDependencies = listOf("libgf_ud_hal.so"),
                    exportedSymbols = listOf("goodix_fod_hbm_enable")
                )
            ),
            components = listOf(
                DiscoveredComponent(
                    id = "c1",
                    name = "libgf_hal.so",
                    category = ComponentCategory.NATIVE_LIB,
                    layer = MechanismLayer.HAL_BINDER,
                    pathOrIdentifier = "/vendor/lib64/libgf_hal.so",
                    status = "Présent",
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    rawProof = "Présent"
                ),
                DiscoveredComponent(
                    id = "c2",
                    name = "android.hardware.fingerprint.xml",
                    category = ComponentCategory.PERMISSION_XML,
                    layer = MechanismLayer.FRAMEWORK,
                    pathOrIdentifier = "/vendor/etc/permissions/android.hardware.fingerprint.xml",
                    status = "Présent",
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    rawProof = "feature fingerprint"
                )
            ),
            modifications = emptyList(),
            properties = mapOf("persist.vendor.sys.fp.fod.location.X_Y" to "540,1920"),
            sysfsNodes = mapOf("/sys/class/drm/card0-DSI-1/fod_hbm" to "1"),
            rcBlocks = listOf("service vendor.fps_hal /vendor/bin/hw/android.hardware.biometrics.fingerprint@2.1-service"),
            permissionEntries = listOf("android.hardware.fingerprint.xml -> android.hardware.fingerprint"),
            eventsCount = 12
        )

        val gsiSession = ImportedFodSession(
            sourceLabel = "gsi_broken.zip",
            sessionId = "session_gsi",
            manufacturerModel = "OEM Phone GSI",
            romType = RomType.GSI_TREBLE,
            romLabel = RomType.GSI_TREBLE.label,
            androidRelease = "15",
            sdkInt = 35,
            rootActive = true,
            outcome = ReconstructionOutcome.ECHEC_DETECTE,
            outcomeLabel = ReconstructionOutcome.ECHEC_DETECTE.label,
            mechanismSummary = "Échec d'enrôlement : HAL Goodix non raccordé et HBM non déclenché.",
            causalExecutionPath = listOf("Settings -> RUPTURE"),
            firstBreakDescription = "HAL FOD non raccordé dans VINTF/Framework",
            firstBreakProof = "binder died",
            flattenedHals = emptyList(),
            components = emptyList(),
            modifications = emptyList(),
            properties = emptyMap(),
            sysfsNodes = emptyMap(),
            rcBlocks = emptyList(),
            permissionEntries = emptyList(),
            eventsCount = 3
        )

        val report = FodComparatorEngine.compare(stockSession, gsiSession)
        assertTrue(report.missingCount >= 5)
        assertTrue(report.diffs.any { it.severity == DiffSeverity.MISSING_IN_B && it.elementName.contains("goodix", ignoreCase = true) })
        assertTrue(report.diffs.any { it.severity == DiffSeverity.MISSING_IN_B && it.elementName.contains("libgf_hal.so") })
        assertTrue(report.diffs.any { it.severity == DiffSeverity.MISSING_IN_B && it.elementName.contains("fod_hbm") })
        assertTrue(report.portingChecklist.isNotEmpty())
    }
}

