package com.example.engine

import com.example.model.ComponentCategory
import com.example.model.ComponentDependency
import com.example.model.DetectedModification
import com.example.model.DeviceProfile
import com.example.model.DiscoveredComponent
import com.example.model.EvidenceLevel
import com.example.model.FodEvent
import com.example.model.MechanismLayer
import com.example.model.MechanismReconstruction
import com.example.model.MechanismStageNode
import com.example.model.ModificationType
import com.example.model.PermissionsState
import com.example.model.ReconstructionOutcome
import com.example.model.RomType
import com.example.model.StageStatus
import java.util.concurrent.atomic.AtomicInteger

import com.example.model.FlattenedHalArtifact

data class AnalysisSynthesis(
    val components: List<DiscoveredComponent>,
    val dependencies: List<ComponentDependency>,
    val modifications: List<DetectedModification>,
    val flattenedHals: List<FlattenedHalArtifact>,
    val reconstruction: MechanismReconstruction
)

/**
 * Moteur de corrélation causale et de reconstruction factuelle 100 % déterministe.
 * Règle absolue : n'invente, ne présume ni n'impose aucune architecture prédéfinie.
 */
object CausalReconstructionEngine {

    private val idCounter = AtomicInteger(1)

    fun synthesize(
        profile: DeviceProfile,
        permissions: PermissionsState,
        artifacts: RawCollectorArtifacts,
        events: List<FodEvent>,
        enrollmentSucceeded: Boolean,
        failureEvidence: String?
    ): AnalysisSynthesis {
        val components = mutableListOf<DiscoveredComponent>()
        val dependencies = mutableListOf<ComponentDependency>()
        val modifications = mutableListOf<DetectedModification>()

        // 1. Cibles d'enrôlement Settings / Biometric
        artifacts.resolvedSettingsTargets.forEach { target ->
            components.add(
                DiscoveredComponent(
                    id = "cmp-${idCounter.getAndIncrement()}",
                    name = target.substringAfter("-> ").substringAfterLast("/"),
                    category = ComponentCategory.SYSTEM_APP,
                    layer = MechanismLayer.SETTINGS_UI,
                    pathOrIdentifier = target,
                    status = "Résolu via PackageManager",
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    rawProof = "Intent queryIntentActivities: $target",
                    discoveryWave = 1
                )
            )
        }

        // 2. Services HAL / Binder / VINTF
        artifacts.discoveredServices.forEach { svc ->
            val lower = svc.lowercase()
            val layer = when {
                lower.contains("surfaceflinger") || lower.contains("display") || lower.contains("light") ->
                    MechanismLayer.DISPLAY_ILLUMINATION
                lower.contains("touch") || lower.contains("input") ->
                    MechanismLayer.TOUCH_DRIVER
                lower.contains("fingerprint") || lower.contains("biometrics") || lower.contains("goodix") ||
                    lower.contains("silead") || lower.contains("fpc") || lower.contains("egistec") ->
                    MechanismLayer.HAL_BINDER
                else -> MechanismLayer.FRAMEWORK
            }
            val cat = if (svc.startsWith("HAL:") || svc.startsWith("VINTF(") || lower.contains("hardware.")) {
                ComponentCategory.SERVICE_HAL
            } else {
                ComponentCategory.BINDER_SERVICE
            }
            components.add(
                DiscoveredComponent(
                    id = "cmp-${idCounter.getAndIncrement()}",
                    name = svc.removePrefix("HAL: ").substringBefore(" ").take(64),
                    category = cat,
                    layer = layer,
                    pathOrIdentifier = svc,
                    status = "Découvert actif",
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    rawProof = svc,
                    discoveryWave = 1
                )
            )
        }

        // 3. Bibliothèques natives & binaires HAL
        artifacts.discoveredHalFiles.forEach { path ->
            val lower = path.lowercase()
            val layer = when {
                lower.contains("display") || lower.contains("drm") -> MechanismLayer.DISPLAY_ILLUMINATION
                lower.contains("touch") -> MechanismLayer.TOUCH_DRIVER
                lower.contains("bin/hw") || lower.contains("/hw/") -> MechanismLayer.HAL_BINDER
                else -> MechanismLayer.KERNEL_TEE
            }
            components.add(
                DiscoveredComponent(
                    id = "cmp-${idCounter.getAndIncrement()}",
                    name = path.substringAfterLast('/'),
                    category = ComponentCategory.NATIVE_LIB,
                    layer = layer,
                    pathOrIdentifier = path,
                    status = "Présent sur partition",
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    rawProof = "Fichier détecté: $path",
                    discoveryWave = 2
                )
            )
        }

        // 4. Nœuds Kernel / Sysfs / Proc
        artifacts.sysfsValues.forEach { (node, value) ->
            val lower = node.lowercase()
            val layer = when {
                lower.contains("drm") || lower.contains("backlight") || lower.contains("hbm") ||
                    lower.contains("lhbm") || lower.contains("dim") || lower.contains("alpha") ||
                    lower.contains("leds") -> MechanismLayer.DISPLAY_ILLUMINATION
                lower.contains("touch") || lower.contains("tp_") -> MechanismLayer.TOUCH_DRIVER
                else -> MechanismLayer.KERNEL_TEE
            }
            components.add(
                DiscoveredComponent(
                    id = "cmp-${idCounter.getAndIncrement()}",
                    name = node.substringAfterLast('/'),
                    category = ComponentCategory.SYSFS_NODE,
                    layer = layer,
                    pathOrIdentifier = node,
                    status = "Valeur observée: ${value.take(48)}",
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    rawProof = "$node = $value",
                    discoveryWave = 2
                )
            )
        }

        // 5. Propriétés système FOD / Biométrie / Phh
        artifacts.matchedProperties.forEach { (key, value) ->
            val lower = key.lowercase()
            val layer = when {
                lower.contains("hbm") || lower.contains("lhbm") || lower.contains("disp") -> MechanismLayer.DISPLAY_ILLUMINATION
                lower.contains("touch") -> MechanismLayer.TOUCH_DRIVER
                lower.contains("hal") || lower.contains("fp") || lower.contains("goodix") || lower.contains("fod") -> MechanismLayer.HAL_BINDER
                else -> MechanismLayer.FRAMEWORK
            }
            components.add(
                DiscoveredComponent(
                    id = "cmp-${idCounter.getAndIncrement()}",
                    name = key,
                    category = ComponentCategory.PROPERTY,
                    layer = layer,
                    pathOrIdentifier = "$key=$value",
                    status = value.ifBlank { "(vide)" },
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    rawProof = "[$key]: [$value]",
                    discoveryWave = 1
                )
            )

            // Détection de surcharges GSI / propriétés personnalisées FOD
            if (lower.contains("phh.fod") || lower.contains("persist.sys.fod") || lower.contains("udfps")) {
                modifications.add(
                    DetectedModification(
                        id = "mod-${idCounter.getAndIncrement()}",
                        type = if (lower.contains("phh")) ModificationType.GSI_SHIM else ModificationType.CUSTOM_PROP,
                        name = key,
                        target = "Propriété runtime FOD",
                        roleSummary = "Paramètre actif influençant le comportement FOD/UDFPS observé ($value).",
                        evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                        rawProof = "[$key]: [$value]"
                    )
                )
            }
        }

        // 6. Overlays RRO, Scripts Init, Modules Magisk / KernelSU
        artifacts.discoveredOverlays.forEach { ov ->
            components.add(
                DiscoveredComponent(
                    id = "cmp-${idCounter.getAndIncrement()}",
                    name = ov.take(56),
                    category = ComponentCategory.OVERLAY_RRO,
                    layer = MechanismLayer.FRAMEWORK,
                    pathOrIdentifier = ov,
                    status = if (ov.contains("[x]")) "Overlay activé" else "Overlay listé",
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    rawProof = "cmd overlay list: $ov",
                    discoveryWave = 3
                )
            )
            modifications.add(
                DetectedModification(
                    id = "mod-${idCounter.getAndIncrement()}",
                    type = ModificationType.RRO_OVERLAY,
                    name = ov.removePrefix("[x] ").removePrefix("[ ] ").trim(),
                    target = "Ressources Framework / SystemUI / Settings",
                    roleSummary = "Surcharge de configuration ou de géométrie/UI FOD.",
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    rawProof = ov
                )
            )
        }

        artifacts.discoveredInitFiles.forEach { rcLine ->
            modifications.add(
                DetectedModification(
                    id = "mod-${idCounter.getAndIncrement()}",
                    type = ModificationType.INIT_SCRIPT,
                    name = rcLine.substringBefore(":").substringAfterLast("/"),
                    target = rcLine.substringBefore(":"),
                    roleSummary = "Déclaration d'initialisation service/permissions FOD au démarrage.",
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    rawProof = rcLine
                )
            )
        }

        artifacts.discoveredModules.forEach { mod ->
            val modType = when {
                permissions.rootDiagnostic.detectedManager.contains("KernelSU", true) -> ModificationType.KERNELSU_MODULE
                mod.contains("lsposed", true) || mod.contains("xposed", true) -> ModificationType.LSPOSED_HOOK
                else -> ModificationType.MAGISK_MODULE
            }
            modifications.add(
                DetectedModification(
                    id = "mod-${idCounter.getAndIncrement()}",
                    type = modType,
                    name = mod,
                    target = "/data/adb",
                    roleSummary = "Module ou script système détecté ; peut injecter des propriétés, hooks ou permissions.",
                    evidenceLevel = EvidenceLevel.CORRELATION_OBSERVEE,
                    rawProof = "Entrée détectée dans /data/adb: $mod"
                )
            )
        }

        if (profile.romType == RomType.GSI_TREBLE) {
            modifications.add(
                DetectedModification(
                    id = "mod-${idCounter.getAndIncrement()}",
                    type = ModificationType.GSI_SHIM,
                    name = "Couche Treble / GSI",
                    target = "System <-> Vendor VNDK",
                    roleSummary = "Architecture GSI détectée : la compatibilité FOD dépend de l'interface HAL vendor et des shims Treble.",
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    rawProof = profile.romEvidence.joinToString(" | ")
                )
            )
        }

        // 6b. Permissions XML & VINTF manifests
        artifacts.halBundle.permissionXmlContents.forEach { (path, content) ->
            components.add(
                DiscoveredComponent(
                    id = "cmp-${idCounter.getAndIncrement()}",
                    name = path.substringAfterLast('/'),
                    category = ComponentCategory.PERMISSION_XML,
                    layer = MechanismLayer.FRAMEWORK,
                    pathOrIdentifier = path,
                    status = "Déclaration permission biométrique",
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    rawProof = content.take(400),
                    discoveryWave = 2
                )
            )
        }
        artifacts.halBundle.vintfManifestContents.forEach { (path, content) ->
            components.add(
                DiscoveredComponent(
                    id = "cmp-${idCounter.getAndIncrement()}",
                    name = "VINTF: ${path.substringAfterLast('/')}",
                    category = ComponentCategory.PERMISSION_XML,
                    layer = MechanismLayer.HAL_BINDER,
                    pathOrIdentifier = path,
                    status = "Manifeste HAL VINTF",
                    evidenceLevel = EvidenceLevel.FAIT_VERIFIE,
                    rawProof = content.take(400),
                    discoveryWave = 2
                )
            )
        }

        // 7. Graphe de dépendances causales entre couches réellement observées
        val distinctLayersPresent = components.groupBy { it.layer }
        val orderedLayers = MechanismLayer.entries.sortedBy { it.order }
        for (i in 0 until orderedLayers.size - 1) {
            val fromLayer = orderedLayers[i]
            val toLayer = orderedLayers[i + 1]
            val fromList = distinctLayersPresent[fromLayer].orEmpty()
            val toList = distinctLayersPresent[toLayer].orEmpty()
            if (fromList.isNotEmpty() && toList.isNotEmpty()) {
                dependencies.add(
                    ComponentDependency(
                        id = "dep-${idCounter.getAndIncrement()}",
                        fromComponentName = fromList.first().name,
                        toComponentName = toList.first().name,
                        relationType = "Chaîne d'exécution observée (${fromLayer.label.substringBefore(".")} -> ${toLayer.label.substringBefore(".")})",
                        evidenceLevel = EvidenceLevel.CORRELATION_OBSERVEE,
                        rawProof = "Source: ${fromList.first().pathOrIdentifier} | Cible: ${toList.first().pathOrIdentifier}"
                    )
                )
            }
        }

        // 8. Évaluation factuelle par couche et détection d'une éventuelle rupture
        val failureEvents = events.filter { it.category == "ERROR_SIGNAL" }
        val firstBreak = failureEvidence ?: failureEvents.firstOrNull()?.rawPayload
        val firstBreakLayer = if (firstBreak != null) inferBreakLayer(firstBreak) else null

        val stages = orderedLayers.map { layer ->
            val layerComponents = distinctLayersPresent[layer].orEmpty()
            val status = when {
                firstBreakLayer == layer && !enrollmentSucceeded -> StageStatus.FIRST_BREAK
                layerComponents.any { it.evidenceLevel == EvidenceLevel.FAIT_VERIFIE } -> StageStatus.VERIFIED_ACTIVE
                layerComponents.isNotEmpty() -> StageStatus.CORRELATED
                !permissions.isEffectiveRootReady && (layer == MechanismLayer.HAL_BINDER || layer == MechanismLayer.KERNEL_TEE) ->
                    StageStatus.UNOBSERVED_LIMITED
                else -> StageStatus.MISSING_OR_REPLACED
            }
            val summary = when (status) {
                StageStatus.FIRST_BREAK ->
                    "Première rupture démontrable détectée sur cette couche pendant l'observation."
                StageStatus.VERIFIED_ACTIVE ->
                    "${layerComponents.size} composant(s) vérifié(s) directement par preuves brutes."
                StageStatus.CORRELATED ->
                    "${layerComponents.size} élément(s) corrélé(s) lors de la session."
                StageStatus.UNOBSERVED_LIMITED ->
                    "Couche non directement observable sans privilèges ROOT (limitation signalée)."
                StageStatus.MISSING_OR_REPLACED ->
                    "Aucun composant conventionnel détecté à ce niveau (possible architecture alternative ou intégrée ailleurs)."
            }
            MechanismStageNode(
                layer = layer,
                status = status,
                componentsCount = layerComponents.size,
                componentNames = layerComponents.take(6).map { it.name },
                factualSummary = summary,
                rawProofs = layerComponents.take(4).map { it.rawProof }
            )
        }

        val outcome = when {
            enrollmentSucceeded -> ReconstructionOutcome.ENROLEMENT_REUSSI
            firstBreak != null -> ReconstructionOutcome.ECHEC_DETECTE
            events.any { it.triggersInvestigation } -> ReconstructionOutcome.OBSERVATION_PARTIELLE
            else -> ReconstructionOutcome.SURVEILLANCE_EN_COURS
        }

        val executionPath = stages
            .filter { it.status == StageStatus.VERIFIED_ACTIVE || it.status == StageStatus.CORRELATED || it.status == StageStatus.FIRST_BREAK }
            .map { "${it.layer.label} [${it.componentNames.joinToString(", ").ifBlank { it.status.label }}]" }

        val summary = buildString {
            when (outcome) {
                ReconstructionOutcome.ENROLEMENT_REUSSI -> {
                    append("Enregistrement FOD/biométrique réussi et confirmé factuellement. ")
                    append("${components.size} composant(s) et ${modifications.size} modification(s)/surcharge(s) observés sur ${profile.romType.label}. ")
                }
                ReconstructionOutcome.ECHEC_DETECTE -> {
                    append("Rupture démontrable détectée lors de l'observation (${firstBreakLayer?.label ?: "couche système"}). ")
                }
                ReconstructionOutcome.OBSERVATION_PARTIELLE -> {
                    append("Investigation déclenchée sur événement d'enrôlement, résultat final non encore confirmé. ")
                }
                ReconstructionOutcome.SURVEILLANCE_EN_COURS -> {
                    append("Collecte de référence active (${components.size} artefacts initiaux identifiés). ")
                }
            }
            if (!permissions.isEffectiveRootReady) {
                append("Périmètre non-ROOT actif : certaines couches HAL/Kernel restent signalées comme limitations.")
            }
        }

        val verifiedFacts = components.count { it.evidenceLevel == EvidenceLevel.FAIT_VERIFIE } +
            events.count { it.evidenceLevel == EvidenceLevel.FAIT_VERIFIE }
        val correlations = dependencies.count { it.evidenceLevel == EvidenceLevel.CORRELATION_OBSERVEE } +
            events.count { it.evidenceLevel == EvidenceLevel.CORRELATION_OBSERVEE }

        val reconstruction = MechanismReconstruction(
            outcome = outcome,
            summary = summary.trim(),
            causalExecutionPath = executionPath,
            firstBreakDescription = if (!enrollmentSucceeded && firstBreak != null) {
                "Rupture observée au niveau : ${firstBreakLayer?.label ?: "Exécution système"}"
            } else null,
            firstBreakProof = if (!enrollmentSucceeded) firstBreak else null,
            stages = stages,
            verifiedFactsCount = verifiedFacts,
            observedCorrelationsCount = correlations,
            limitationsCount = permissions.limitations.size,
            generatedAtIso = RootDiagnostics.isoNow()
        )

        return AnalysisSynthesis(
            components = components,
            dependencies = dependencies,
            modifications = modifications,
            flattenedHals = artifacts.halBundle.flattenedHals,
            reconstruction = reconstruction
        )
    }

    private fun inferBreakLayer(rawError: String): MechanismLayer {
        val lower = rawError.lowercase()
        return when {
            lower.contains("avc:") || lower.contains("/sys/") || lower.contains("kernel") || lower.contains("tee") || lower.contains("qsee") ->
                MechanismLayer.KERNEL_TEE
            lower.contains("hbm") || lower.contains("lhbm") || lower.contains("surfaceflinger") || lower.contains("display") || lower.contains("dim") ->
                MechanismLayer.DISPLAY_ILLUMINATION
            lower.contains("touch") || lower.contains("input") ->
                MechanismLayer.TOUCH_DRIVER
            lower.contains("hal") || lower.contains("binder") || lower.contains("fingerprint") || lower.contains("goodix") || lower.contains("silead") ->
                MechanismLayer.HAL_BINDER
            lower.contains("enroll") || lower.contains("settings") ->
                MechanismLayer.SETTINGS_UI
            else -> MechanismLayer.FRAMEWORK
        }
    }
}
