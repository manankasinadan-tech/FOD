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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.engine.FodEngineState
import com.example.model.EvidenceLevel
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.EmeraldVerified
import com.example.ui.theme.SlateOutline

private enum class DiscoveryFilterTab(val label: String) {
    COMPONENTS("Composants"),
    PATCHES("Patches & Hooks"),
    DEPENDENCIES("Dépendances"),
    EVENTS("Événements"),
    EXPORTS("Exports /FOD/")
}

@Composable
fun DiscoveriesScreen(
    state: FodEngineState,
    onInspectProof: (InspectedProof) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(DiscoveryFilterTab.COMPONENTS) }
    var searchQuery by remember { mutableStateOf("") }

    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 840.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filtrer par HAL, sysfs, propriété, patch...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("discoveries_search_input")
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DiscoveryFilterTab.entries.forEach { tab ->
                    val count = when (tab) {
                        DiscoveryFilterTab.COMPONENTS -> state.components.size
                        DiscoveryFilterTab.PATCHES -> state.modifications.size
                        DiscoveryFilterTab.DEPENDENCIES -> state.dependencies.size
                        DiscoveryFilterTab.EVENTS -> state.events.size
                        DiscoveryFilterTab.EXPORTS -> state.exportState.exportedFiles.size
                    }
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        label = { Text("${tab.label} ($count)") }
                    )
                }
            }
        }

        val q = searchQuery.trim()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (selectedTab) {
                DiscoveryFilterTab.COMPONENTS -> {
                    val filtered = state.components.filter {
                        q.isBlank() || it.name.contains(q, true) || it.pathOrIdentifier.contains(q, true) || it.category.label.contains(q, true)
                    }
                    if (filtered.isEmpty()) {
                        item { EmptyDiscoveryCard("Aucun composant ne correspond au filtre actuel.") }
                    } else {
                        items(filtered, key = { it.id }) { cmp ->
                            DiscoveryItemCard(
                                tag = "${cmp.category.label} • Vague ${cmp.discoveryWave}",
                                title = cmp.name,
                                subtitle = cmp.pathOrIdentifier,
                                detail = "${cmp.layer.label} — ${cmp.status}",
                                level = cmp.evidenceLevel,
                                onClick = {
                                    onInspectProof(
                                        InspectedProof(
                                            title = cmp.name,
                                            subtitle = "${cmp.category.label} (${cmp.layer.label})",
                                            level = cmp.evidenceLevel,
                                            rawLines = listOf(
                                                "Identifiant: ${cmp.pathOrIdentifier}",
                                                "Statut: ${cmp.status}",
                                                "Preuve: ${cmp.rawProof}"
                                            )
                                        )
                                    )
                                }
                            )
                        }
                    }
                }

                DiscoveryFilterTab.PATCHES -> {
                    val filtered = state.modifications.filter {
                        q.isBlank() || it.name.contains(q, true) || it.roleSummary.contains(q, true) || it.type.label.contains(q, true)
                    }
                    if (filtered.isEmpty()) {
                        item {
                            EmptyDiscoveryCard(
                                if (state.permissions.isEffectiveRootReady) {
                                    "Aucun patch, overlay RRO ou module détecté pour le moment."
                                } else {
                                    "Aucun patch visible en mode sans ROOT. Activez ROOT dans Paramètres pour inspecter /data/adb, init.rc et les overlays."
                                }
                            )
                        }
                    } else {
                        items(filtered, key = { it.id }) { mod ->
                            DiscoveryItemCard(
                                tag = mod.type.label,
                                title = mod.name,
                                subtitle = mod.target,
                                detail = mod.roleSummary,
                                level = mod.evidenceLevel,
                                onClick = {
                                    onInspectProof(
                                        InspectedProof(
                                            title = mod.name,
                                            subtitle = "${mod.type.label} -> ${mod.target}",
                                            level = mod.evidenceLevel,
                                            rawLines = listOf(mod.roleSummary, mod.rawProof)
                                        )
                                    )
                                }
                            )
                        }
                    }
                }

                DiscoveryFilterTab.DEPENDENCIES -> {
                    val filtered = state.dependencies.filter {
                        q.isBlank() || it.fromComponentName.contains(q, true) || it.toComponentName.contains(q, true)
                    }
                    if (filtered.isEmpty()) {
                        item { EmptyDiscoveryCard("Aucune relation causale inter-couches corrélée pour l'instant.") }
                    } else {
                        items(filtered, key = { it.id }) { dep ->
                            DiscoveryItemCard(
                                tag = "Dépendance causale",
                                title = "${dep.fromComponentName}  ->  ${dep.toComponentName}",
                                subtitle = dep.relationType,
                                detail = dep.rawProof,
                                level = dep.evidenceLevel,
                                onClick = {
                                    onInspectProof(
                                        InspectedProof(
                                            title = "${dep.fromComponentName} -> ${dep.toComponentName}",
                                            subtitle = dep.relationType,
                                            level = dep.evidenceLevel,
                                            rawLines = listOf(dep.rawProof)
                                        )
                                    )
                                }
                            )
                        }
                    }
                }

                DiscoveryFilterTab.EVENTS -> {
                    val filtered = state.events.reversed().filter {
                        q.isBlank() || it.title.contains(q, true) || it.rawPayload.contains(q, true) || it.source.contains(q, true)
                    }
                    if (filtered.isEmpty()) {
                        item { EmptyDiscoveryCard("Aucun événement enregistré dans la fenêtre courante.") }
                    } else {
                        items(filtered, key = { it.id }) { evt ->
                            EventRowCard(
                                event = evt,
                                onClick = {
                                    onInspectProof(
                                        InspectedProof(
                                            title = evt.title,
                                            subtitle = "${evt.timestampIso} • ${evt.source} (${evt.category})",
                                            level = evt.evidenceLevel,
                                            rawLines = listOf(evt.rawPayload)
                                        )
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)
                            )
                        }
                    }
                }

                DiscoveryFilterTab.EXPORTS -> {
                    item {
                        ForensicCard(modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = if (state.exportState.isPrimarySdcardFod) {
                                        "Stockage actif : /sdcard/FOD/"
                                    } else {
                                        "Stockage en zone de repli (permission /sdcard/FOD/ requise)"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (state.exportState.isPrimarySdcardFod) EmeraldVerified else CyanTelemetry
                                )
                                Text(
                                    text = state.exportState.sessionDirectoryPath.ifBlank { "Aucun export généré" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    val filtered = state.exportState.exportedFiles.filter {
                        q.isBlank() || it.relativePath.contains(q, true) || it.category.contains(q, true)
                    }
                    items(filtered, key = { it.relativePath }) { fileEntry ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, SlateOutline),
                            modifier = Modifier
                                .fillMaxWidth()
                                .widthIn(max = 840.dp)
                                .clickable {
                                    val dump = state.rawArtifacts.rawDumps[fileEntry.relativePath.substringAfterLast('/')]
                                    onInspectProof(
                                        InspectedProof(
                                            title = fileEntry.relativePath,
                                            subtitle = "${fileEntry.category} • ${fileEntry.sizeBytes} octets",
                                            level = EvidenceLevel.FAIT_VERIFIE,
                                            rawLines = dump?.lines()?.take(120) ?: listOf(
                                                "Chemin complet: ${state.exportState.sessionDirectoryPath}/${fileEntry.relativePath}",
                                                "Taille: ${fileEntry.sizeBytes} octets"
                                            )
                                        )
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = fileEntry.relativePath,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = CyanTelemetry
                                    )
                                    Text(
                                        text = fileEntry.category,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "${fileEntry.sizeBytes} o",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryItemCard(
    tag: String,
    title: String,
    subtitle: String,
    detail: String,
    level: EvidenceLevel,
    onClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, SlateOutline),
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 840.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanTelemetry,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                EvidenceBadge(level = level)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyDiscoveryCard(message: String) {
    ForensicCard(modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
