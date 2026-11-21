package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.ComparisonDiffItem
import com.example.model.DiffSeverity
import com.example.model.ImportedFodSession
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonBreak
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.CyanTelemetryContainer
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldVerified
import com.example.ui.theme.SlateOutline
import com.example.ui.theme.SlateSurfaceElevated
import com.example.viewmodel.ComparatorUiState
import java.io.File

private enum class DiffFilter(val label: String) {
    MISSING("Manquants dans B"),
    DIVERGENT("Divergences"),
    ALL("Tout")
}

@Composable
fun ComparatorScreen(
    comparatorState: ComparatorUiState,
    onImportZipFromPickerA: () -> Unit,
    onImportZipFromPickerB: () -> Unit,
    onImportLocalZip: (slotA: Boolean, file: File) -> Unit,
    onUseLiveSession: (slotA: Boolean) -> Unit,
    onClearComparator: () -> Unit,
    modifier: Modifier = Modifier
) {
    var localPickerForSlotA by remember { mutableStateOf<Boolean?>(null) }
    var diffFilter by remember { mutableStateOf(DiffFilter.MISSING) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // En-tête Comparateur
        item {
            ForensicCard(modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.CompareArrows, contentDescription = null, tint = CyanTelemetry)
                            Text(
                                text = "Comparateur Stock vs GSI",
                                style = MaterialTheme.typography.headlineSmall
                            )
                        }
                        if (comparatorState.sessionA != null || comparatorState.sessionB != null) {
                            IconButton(onClick = onClearComparator) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Réinitialiser la comparaison")
                            }
                        }
                    }
                    Text(
                        text = "Importez deux archives ZIP (A : système où le FOD fonctionne ; B : GSI ou système où le FOD échoue) pour comparer leurs mécanismes réels et isoler ce qui manque pour le portage.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Importateur A (Référence fonctionnelle)
        item {
            ZipImporterSlotCard(
                slotLabel = "SOURCE A — SYSTÈME FONCTIONNEL (EX. ROM STOCK)",
                accentColor = EmeraldVerified,
                imported = comparatorState.sessionA,
                pickZipTag = "import_zip_slot_a",
                useLiveTag = "use_live_slot_a",
                localZipsCount = comparatorState.localZipFiles.size,
                onPickFromDevice = onImportZipFromPickerA,
                onPickFromLocalFod = { localPickerForSlotA = true },
                onUseLiveSession = { onUseLiveSession(true) },
                modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)
            )
        }

        // Importateur B (Cible défaillante / GSI)
        item {
            ZipImporterSlotCard(
                slotLabel = "SOURCE B — CIBLE / GSI NON FONCTIONNEL",
                accentColor = AmberWarning,
                imported = comparatorState.sessionB,
                pickZipTag = "import_zip_slot_b",
                useLiveTag = "use_live_slot_b",
                localZipsCount = comparatorState.localZipFiles.size,
                onPickFromDevice = onImportZipFromPickerB,
                onPickFromLocalFod = { localPickerForSlotA = false },
                onUseLiveSession = { onUseLiveSession(false) },
                modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)
            )
        }

        val report = comparatorState.report
        if (report == null) {
            item {
                ForensicCard(modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)) {
                    Text(
                        text = "Importez les deux archives ZIP (Source A et Source B) pour lancer automatiquement l'analyse différentielle de portage FOD.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // 1. Synthèse comparative des deux mécanismes
            item {
                ForensicCard(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp),
                    borderColor = CyanTelemetry.copy(alpha = 0.6f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Résultat de la comparaison factuelle",
                            style = MaterialTheme.typography.titleMedium,
                            color = CyanTelemetry
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DiffCounterPill("${report.missingCount} manquants dans B", CrimsonBreak, CrimsonContainer, Modifier.weight(1f))
                            DiffCounterPill("${report.divergentCount} divergences", AmberWarning, AmberContainer, Modifier.weight(1f))
                            DiffCounterPill("${report.matchedCount} communs", EmeraldVerified, EmeraldContainer, Modifier.weight(1f))
                        }

                        Surface(
                            color = EmeraldContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, EmeraldVerified.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Mécanisme de A (Fonctionnel)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldVerified,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(report.mechanismASummary, style = MaterialTheme.typography.bodyMedium)
                                report.workingFactorsInA.forEach { factor ->
                                    Text("• $factor", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        Surface(
                            color = AmberContainer.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "Mécanisme observé sur B (GSI / Cible)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AmberWarning,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(report.mechanismBSummary, style = MaterialTheme.typography.bodyMedium)
                                report.findingsInB.forEach { f ->
                                    Text("• $f", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // 2. Plan de portage factuel (ce qui manque au GSI B)
            if (report.portingChecklist.isNotEmpty()) {
                item {
                    ForensicCard(
                        modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp),
                        borderColor = CrimsonBreak.copy(alpha = 0.5f)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.Build, contentDescription = null, tint = CrimsonBreak)
                                Text(
                                    text = "Éléments manquants sur B pour le portage FOD",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            report.portingChecklist.forEach { action ->
                                Surface(
                                    color = SlateSurfaceElevated,
                                    shape = RoundedCornerShape(10.dp),
                                    border = BorderStroke(1.dp, SlateOutline),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                        Text(
                                            text = "[${action.priority}] ${action.layer} — ${action.title}",
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (action.priority == "CRITIQUE") CrimsonBreak else AmberWarning
                                        )
                                        Text(
                                            text = action.factualReason,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 3. Filtres et Diff détaillé par composant
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 840.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiffFilter.entries.forEach { f ->
                        FilterChip(
                            selected = diffFilter == f,
                            onClick = { diffFilter = f },
                            label = { Text(f.label) }
                        )
                    }
                }
            }

            val filteredDiffs = report.diffs.filter { item ->
                when (diffFilter) {
                    DiffFilter.MISSING -> item.severity == DiffSeverity.MISSING_IN_B
                    DiffFilter.DIVERGENT -> item.severity == DiffSeverity.DIVERGENT || item.severity == DiffSeverity.EXTRA_IN_B
                    DiffFilter.ALL -> true
                }
            }

            if (filteredDiffs.isEmpty()) {
                item {
                    ForensicCard(modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)) {
                        Text(
                            text = "Aucune différence dans cette catégorie.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(filteredDiffs, key = { it.id }) { diff ->
                    DiffItemCard(
                        diff = diff,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)
                    )
                }
            }
        }
    }

    // Sélecteur d'archive ZIP locale présente dans /sdcard/FOD/
    localPickerForSlotA?.let { isSlotA ->
        AlertDialog(
            onDismissRequest = { localPickerForSlotA = null },
            title = { Text(if (isSlotA) "Archives locales pour A" else "Archives locales pour B") },
            text = {
                if (comparatorState.localZipFiles.isEmpty()) {
                    Text("Aucune archive .zip trouvée dans /sdcard/FOD/ ou le dossier local.")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        comparatorState.localZipFiles.take(10).forEach { file ->
                            Surface(
                                color = SlateSurfaceElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, SlateOutline),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onImportLocalZip(isSlotA, file)
                                        localPickerForSlotA = null
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Filled.FolderZip, contentDescription = null, tint = CyanTelemetry)
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(file.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("${file.length() / 1024} Ko", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { localPickerForSlotA = null }) {
                    Text("Fermer")
                }
            }
        )
    }
}

@Composable
private fun ZipImporterSlotCard(
    slotLabel: String,
    accentColor: androidx.compose.ui.graphics.Color,
    imported: ImportedFodSession?,
    pickZipTag: String,
    useLiveTag: String,
    localZipsCount: Int,
    onPickFromDevice: () -> Unit,
    onPickFromLocalFod: () -> Unit,
    onUseLiveSession: () -> Unit,
    modifier: Modifier = Modifier
) {
    ForensicCard(
        modifier = modifier,
        borderColor = if (imported != null) accentColor.copy(alpha = 0.65f) else SlateOutline
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = slotLabel,
                style = MaterialTheme.typography.labelSmall,
                color = accentColor,
                fontWeight = FontWeight.Bold
            )

            if (imported != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                    Text(
                        text = "${imported.manufacturerModel} • ${imported.romLabel}",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Text(
                    text = "Archive : ${imported.sourceLabel} | Android ${imported.androidRelease} | ${imported.outcomeLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${imported.flattenedHals.size} HALs à plat • ${imported.components.size} composants • ${imported.sysfsNodes.size} nœuds sysfs",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanTelemetry
                )
            } else {
                Text(
                    text = "Aucune archive ZIP importée dans cet emplacement.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPickFromDevice,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .minimumInteractiveComponentSize()
                        .testTag(pickZipTag)
                ) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Importer ZIP")
                }

                OutlinedButton(
                    onClick = onPickFromLocalFod,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Filled.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("/FOD/ ($localZipsCount)")
                }
            }

            OutlinedButton(
                onClick = onUseLiveSession,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag(useLiveTag)
            ) {
                Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Utiliser la session active de cet appareil")
            }
        }
    }
}

@Composable
private fun DiffCounterPill(
    label: String,
    fg: androidx.compose.ui.graphics.Color,
    bg: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun DiffItemCard(
    diff: ComparisonDiffItem,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when (diff.severity) {
        DiffSeverity.MISSING_IN_B -> CrimsonContainer to CrimsonBreak
        DiffSeverity.DIVERGENT -> AmberContainer to AmberWarning
        DiffSeverity.PRESENT_IN_BOTH -> EmeraldContainer to EmeraldVerified
        DiffSeverity.EXTRA_IN_B -> CyanTelemetryContainer to CyanTelemetry
    }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = diff.layerName,
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanTelemetry,
                    modifier = Modifier.weight(1f)
                )
                Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
                    Text(
                        text = diff.severity.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = fg,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }
            Text(diff.elementName, style = MaterialTheme.typography.titleSmall)
            Text("A (Fonctionnel) : ${diff.detailInA}", style = MaterialTheme.typography.bodySmall, color = EmeraldVerified)
            Text("B (Cible) : ${diff.detailInB}", style = MaterialTheme.typography.bodySmall, color = fg)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
                Text(diff.portingImpact, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
