package com.example.engine

import com.example.model.ComparisonDiffItem
import com.example.model.ComponentCategory
import com.example.model.DiffSeverity
import com.example.model.FodComparisonReport
import com.example.model.ImportedFodSession
import com.example.model.PortingActionItem
import java.util.concurrent.atomic.AtomicInteger

/**
 * Moteur déterministe de comparaison factuelle entre une session fonctionnelle (ex. Stock A)
 * et une session défaillante (ex. GSI B) pour diagnostiquer ce qui manque au portage FOD.
 */
object FodComparatorEngine {

    private val diffId = AtomicInteger(1)

    fun compare(sessionA: ImportedFodSession, sessionB: ImportedFodSession): FodComparisonReport {
        val diffs = mutableListOf<ComparisonDiffItem>()
        val checklist = mutableListOf<PortingActionItem>()

        // 1. Interfaces HAL (AIDL / HIDL / VINTF)
        val halsA = sessionA.flattenedHals.associateBy { normalizeHalName(it.serviceName) }
        val halsB = sessionB.flattenedHals.associateBy { normalizeHalName(it.serviceName) }
        halsA.forEach { (norm, halA) ->
            val halB = halsB[norm]
            if (halB == null) {
                diffs.add(
                    ComparisonDiffItem(
                        id = "diff-${diffId.getAndIncrement()}",
                        layerName = "HAL Biométrique (AIDL/HIDL)",
                        elementName = halA.serviceName,
                        severity = DiffSeverity.MISSING_IN_B,
                        detailInA = "${halA.interfaceType} • binaire ${halA.binaryPath}",
                        detailInB = "Interface absente ou non enregistrée dans les services/VINTF",
                        portingImpact = "Le framework de B ne peut pas dialoguer avec ce service HAL FOD."
                    )
                )
                checklist.add(
                    PortingActionItem(
                        priority = "CRITIQUE",
                        layer = "HAL / VINTF",
                        title = "Exposer/raccorder ${halA.serviceName}",
                        factualReason = "Actif sur ${sessionA.romLabel} (${halA.binaryPath}), absent sur ${sessionB.romLabel}."
                    )
                )
            } else {
                val libsMissing = halA.sharedLibraries.map { it.substringAfterLast('/') } -
                    halB.sharedLibraries.map { it.substringAfterLast('/') }.toSet()
                val dtMissing = halA.dtNeededDependencies - halB.dtNeededDependencies.toSet()
                if (libsMissing.isNotEmpty() || dtMissing.isNotEmpty()) {
                    diffs.add(
                        ComparisonDiffItem(
                            id = "diff-${diffId.getAndIncrement()}",
                            layerName = "Bibliothèques & Dépendances ELF (.so)",
                            elementName = halA.serviceName,
                            severity = DiffSeverity.DIVERGENT,
                            detailInA = "Libs: ${halA.sharedLibraries.joinToString { it.substringAfterLast('/') }} | DT_NEEDED: ${halA.dtNeededDependencies.take(6).joinToString()}",
                            detailInB = "Manquants côté B: ${(libsMissing + dtMissing).distinct().joinToString()}",
                            portingImpact = "Dépendance native manquante ou liaison dynamique rompue côté B."
                        )
                    )
                    checklist.add(
                        PortingActionItem(
                            priority = "HAUTE",
                            layer = "Bibliothèques ELF",
                            title = "Vérifier dépendances ${(libsMissing + dtMissing).distinct().take(4).joinToString()}",
                            factualReason = "Requis par ${halA.serviceName} dans l'archive fonctionnelle A."
                        )
                    )
                } else {
                    diffs.add(
                        ComparisonDiffItem(
                            id = "diff-${diffId.getAndIncrement()}",
                            layerName = "HAL Biométrique (AIDL/HIDL)",
                            elementName = halA.serviceName,
                            severity = DiffSeverity.PRESENT_IN_BOTH,
                            detailInA = halA.interfaceType,
                            detailInB = halB.interfaceType,
                            portingImpact = "Interface HAL présente des deux côtés."
                        )
                    )
                }
            }
        }

        // 2. Bibliothèques natives & binaires (.so / /bin/hw)
        val libsA = sessionA.components
            .filter { it.category == ComponentCategory.NATIVE_LIB }
            .associateBy { it.name.lowercase() }
        val libsB = sessionB.components
            .filter { it.category == ComponentCategory.NATIVE_LIB }
            .associateBy { it.name.lowercase() }

        libsA.forEach { (key, cmpA) ->
            if (!libsB.containsKey(key)) {
                diffs.add(
                    ComparisonDiffItem(
                        id = "diff-${diffId.getAndIncrement()}",
                        layerName = "Bibliothèques & Binaires Vendor",
                        elementName = cmpA.name,
                        severity = DiffSeverity.MISSING_IN_B,
                        detailInA = cmpA.pathOrIdentifier,
                        detailInB = "Non détecté dans B",
                        portingImpact = "Binaire ou bibliothèque HAL présent dans A mais non découvert/accessible dans B."
                    )
                )
            }
        }

        // 3. Scripts Init (.rc)
        val rcNamesA = sessionA.rcBlocks.map { it.substringBefore(":\n").substringAfterLast('/') }.distinct()
        val rcNamesB = sessionB.rcBlocks.map { it.substringBefore(":\n").substringAfterLast('/') }.toSet()
        rcNamesA.forEach { rc ->
            if (rc !in rcNamesB) {
                diffs.add(
                    ComparisonDiffItem(
                        id = "diff-${diffId.getAndIncrement()}",
                        layerName = "Scripts Init (.rc)",
                        elementName = rc,
                        severity = DiffSeverity.MISSING_IN_B,
                        detailInA = "Script d'initialisation FOD/biométrique actif dans A",
                        detailInB = "Absent dans B",
                        portingImpact = "Le service ou les permissions chmod/chown de démarrage définis dans $rc peuvent manquer."
                    )
                )
                checklist.add(
                    PortingActionItem(
                        priority = "HAUTE",
                        layer = "Init (.rc)",
                        title = "Vérifier l'initialisation définie dans $rc",
                        factualReason = "Présent dans l'archive fonctionnelle A, absent dans B."
                    )
                )
            }
        }

        // 4. Permissions XML & Manifests VINTF
        val permA = sessionA.components
            .filter { it.category == ComponentCategory.PERMISSION_XML }
            .associateBy { it.name.lowercase() }
        val permB = sessionB.components
            .filter { it.category == ComponentCategory.PERMISSION_XML }
            .associateBy { it.name.lowercase() }
        permA.forEach { (k, pA) ->
            if (!permB.containsKey(k)) {
                diffs.add(
                    ComparisonDiffItem(
                        id = "diff-${diffId.getAndIncrement()}",
                        layerName = "Permissions XML & VINTF",
                        elementName = pA.name,
                        severity = DiffSeverity.MISSING_IN_B,
                        detailInA = pA.pathOrIdentifier,
                        detailInB = "Absent dans B",
                        portingImpact = "Une permission matérielle (ex. android.hardware.fingerprint) ou déclaration VINTF manque côté B."
                    )
                )
                checklist.add(
                    PortingActionItem(
                        priority = "HAUTE",
                        layer = "Permissions / VINTF",
                        title = "Porter ou vérifier ${pA.name}",
                        factualReason = "Déclaration présente dans A (${pA.pathOrIdentifier}), manquante dans B."
                    )
                )
            }
        }

        // 5. Nœuds Kernel / Sysfs / HBM / Tactile
        val sysfsA = sessionA.sysfsNodes
        val sysfsB = sessionB.sysfsNodes
        sysfsA.forEach { (node, valA) ->
            val valB = sysfsB[node]
            when {
                valB == null -> {
                    diffs.add(
                        ComparisonDiffItem(
                            id = "diff-${diffId.getAndIncrement()}",
                            layerName = "Affichage (HBM) & Nœuds Sysfs",
                            elementName = node.substringAfterLast('/'),
                            severity = DiffSeverity.MISSING_IN_B,
                            detailInA = "$node = $valA",
                            detailInB = "Nœud non découvert/non lu dans B",
                            portingImpact = "Le chemin sysfs d'illumination HBM/LHBM ou tactile observé dans A n'est pas sollicité dans B."
                        )
                    )
                    checklist.add(
                        PortingActionItem(
                            priority = "MOYENNE",
                            layer = "Sysfs / HBM",
                            title = "Vérifier l'accès au nœud ${node.substringAfterLast('/')}",
                            factualReason = "Observé à '$valA' sur A ($node), absent/non lu sur B."
                        )
                    )
                }
                valA != valB -> {
                    diffs.add(
                        ComparisonDiffItem(
                            id = "diff-${diffId.getAndIncrement()}",
                            layerName = "Affichage (HBM) & Nœuds Sysfs",
                            elementName = node.substringAfterLast('/'),
                            severity = DiffSeverity.DIVERGENT,
                            detailInA = valA,
                            detailInB = valB,
                            portingImpact = "État du nœud différent entre le système fonctionnel et la cible."
                        )
                    )
                }
            }
        }

        // 6. Propriétés système FOD / PHH / Vendor & Overlays RRO
        sessionA.properties.forEach { (key, valA) ->
            val valB = sessionB.properties[key]
            if (valB == null) {
                diffs.add(
                    ComparisonDiffItem(
                        id = "diff-${diffId.getAndIncrement()}",
                        layerName = "Propriétés FOD & Configuration",
                        elementName = key,
                        severity = DiffSeverity.MISSING_IN_B,
                        detailInA = valA,
                        detailInB = "(propriété absente)",
                        portingImpact = "Propriété FOD présente dans A mais absente dans B."
                    )
                )
            } else if (valA != valB) {
                diffs.add(
                    ComparisonDiffItem(
                        id = "diff-${diffId.getAndIncrement()}",
                        layerName = "Propriétés FOD & Configuration",
                        elementName = key,
                        severity = DiffSeverity.DIVERGENT,
                        detailInA = valA,
                        detailInB = valB,
                        portingImpact = "Valeur divergente susceptible de modifier le comportement FOD."
                    )
                )
            }
        }

        val rrosA = sessionA.modifications.map { it.name.lowercase() to it }.toMap()
        val rrosB = sessionB.modifications.map { it.name.lowercase() }.toSet()
        rrosA.forEach { (k, modA) ->
            if (k !in rrosB) {
                diffs.add(
                    ComparisonDiffItem(
                        id = "diff-${diffId.getAndIncrement()}",
                        layerName = "Overlays RRO, Patches & Hooks",
                        elementName = modA.name,
                        severity = DiffSeverity.MISSING_IN_B,
                        detailInA = "${modA.type.label} : ${modA.roleSummary}",
                        detailInB = "Absent dans B",
                        portingImpact = "Surcharge ou module présent sur le système fonctionnel absent sur B."
                    )
                )
            }
        }

        // Propriétés spécifiques à B (ex. shims PHH sur GSI)
        sessionB.properties.forEach { (key, valB) ->
            if (!sessionA.properties.containsKey(key) && key.contains("phh", ignoreCase = true)) {
                diffs.add(
                    ComparisonDiffItem(
                        id = "diff-${diffId.getAndIncrement()}",
                        layerName = "Spécifique GSI (B)",
                        elementName = key,
                        severity = DiffSeverity.EXTRA_IN_B,
                        detailInA = "(absent de la ROM A)",
                        detailInB = valB,
                        portingImpact = "Paramètre GSI spécifique détecté côté B."
                    )
                )
            }
        }

        // Synthèse du mécanisme de chacun
        val workingFactorsInA = buildList {
            add("Système A : ${sessionA.manufacturerModel} (${sessionA.romLabel}) — ${sessionA.outcomeLabel}")
            add("${sessionA.flattenedHals.size} fiche(s) HAL, ${sessionA.components.size} composant(s), ${sessionA.rcBlocks.size} fichier(s) .rc")
            if (sessionA.causalExecutionPath.isNotEmpty()) {
                addAll(sessionA.causalExecutionPath.take(6))
            }
        }

        val findingsInB = buildList {
            add("Système B : ${sessionB.manufacturerModel} (${sessionB.romLabel}) — ${sessionB.outcomeLabel}")
            if (!sessionB.firstBreakDescription.isNullOrBlank()) {
                add("Rupture détectée dans B : ${sessionB.firstBreakDescription}")
            }
            if (!sessionB.firstBreakProof.isNullOrBlank()) {
                add("Preuve rupture B : ${sessionB.firstBreakProof}")
            }
            add("${sessionB.flattenedHals.size} fiche(s) HAL, ${sessionB.components.size} composant(s), ${sessionB.rcBlocks.size} fichier(s) .rc")
        }

        val missingCount = diffs.count { it.severity == DiffSeverity.MISSING_IN_B }
        val divergentCount = diffs.count { it.severity == DiffSeverity.DIVERGENT }
        val matchedCount = diffs.count { it.severity == DiffSeverity.PRESENT_IN_BOTH }

        val mechASum = "${sessionA.mechanismSummary} (${sessionA.flattenedHals.size} HALs, ${sessionA.sysfsNodes.size} nœuds sysfs)."
        val mechBSum = "${sessionB.mechanismSummary} ($missingCount élément(s) manquant(s) vs A, $divergentCount divergence(s))."

        return FodComparisonReport(
            sessionA = sessionA,
            sessionB = sessionB,
            mechanismASummary = mechASum,
            mechanismBSummary = mechBSum,
            workingFactorsInA = workingFactorsInA,
            findingsInB = findingsInB,
            diffs = diffs,
            portingChecklist = checklist.distinctBy { it.title },
            missingCount = missingCount,
            divergentCount = divergentCount,
            matchedCount = matchedCount,
            generatedAtIso = RootDiagnostics.isoNow()
        )
    }

    private fun normalizeHalName(raw: String): String {
        return raw.substringBefore("@").substringBefore("/").substringBefore(" ").lowercase().trim()
    }
}
