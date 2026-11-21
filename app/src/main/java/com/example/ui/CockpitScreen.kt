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
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.engine.FodEngineState
import com.example.model.EnginePhase
import com.example.model.EvidenceLevel
import com.example.model.FodEvent
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.CyanTelemetryContainer
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldVerified
import com.example.ui.theme.SlateOutline
import com.example.ui.theme.SlateSurfaceElevated

@Composable
fun CockpitScreen(
    state: FodEngineState,
    onOpenFingerprintSettings: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onFinalizeReconstruction: () -> Unit,
    onStartNewSession: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToMechanism: () -> Unit,
    onInspectEvent: (FodEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val isMonitoringRunning = state.phase == EnginePhase.MONITORING_PASSIVE ||
        state.phase == EnginePhase.INVESTIGATING

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Carte Héro Télémétrie + Statut Moteur
        item {
            ForensicCard(
                modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp),
                borderColor = when (state.phase) {
                    EnginePhase.INVESTIGATING -> CyanTelemetry.copy(alpha = 0.6f)
                    EnginePhase.RECONSTRUCTED -> EmeraldVerified.copy(alpha = 0.6f)
                    else -> SlateOutline
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Surface(
                                color = when (state.phase) {
                                    EnginePhase.RECONSTRUCTED -> EmeraldContainer
                                    EnginePhase.PAUSED -> AmberContainer
                                    else -> CyanTelemetryContainer
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = state.phase.label.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (state.phase) {
                                        EnginePhase.RECONSTRUCTED -> EmeraldVerified
                                        EnginePhase.PAUSED -> AmberWarning
                                        else -> CyanTelemetry
                                    },
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "FOD DETECTOR",
                                style = MaterialTheme.typography.headlineLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = state.statusBanner,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        FodTelemetryCanvas(
                            phase = state.phase,
                            modifier = Modifier.size(88.dp)
                        )
                    }

                    if (state.isBusyAction || state.phase == EnginePhase.INITIALIZING) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = CyanTelemetry,
                            trackColor = SlateSurfaceElevated
                        )
                    }

                    // Compteurs factuels en direct
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MetricTile(
                            label = "Événements",
                            value = state.events.size.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Composants",
                            value = state.components.size.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Patches",
                            value = state.modifications.size.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = "Faits",
                            value = (state.reconstruction?.verifiedFactsCount ?: 0).toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // 2. Actions principales (Ouvrir Paramètres Empreinte + Contrôles)
        item {
            ForensicCard(
                modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Procédure d'analyse en direct",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "1. L'état initial est capturé automatiquement.\n2. Ouvrez les Paramètres Android pour enregistrer une empreinte.\n3. Le premier événement FOD déclenche l'investigation jusqu'au résultat final.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Button(
                        onClick = onOpenFingerprintSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("open_fingerprint_settings_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanTelemetry,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = "Ouvrir Paramètres Empreinte"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Ouvrir Paramètres Empreinte",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = onToggleMonitoring,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("toggle_monitoring_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = if (isMonitoringRunning) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (isMonitoringRunning) "Suspendre" else "Activer")
                        }

                        FilledTonalButton(
                            onClick = onFinalizeReconstruction,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("finalize_reconstruction_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reconstruire")
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onNavigateToMechanism,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("view_mechanism_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Voir cartographie")
                        }
                        OutlinedButton(
                            onClick = onStartNewSession,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("new_session_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Nouvelle session")
                        }
                    }
                }
            }
        }

        // 3. État des autorisations & capacités (non-bloquant)
        item {
            ForensicCard(
                modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Capacités d'accès",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "${state.permissions.limitations.size} limitation(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.permissions.limitations.isEmpty()) EmeraldVerified else AmberWarning
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CapabilityPill(
                            label = if (state.permissions.sdcardFodWritable) "/sdcard/FOD/ OK" else "Stockage repli",
                            icon = Icons.Filled.Folder,
                            active = state.permissions.sdcardFodWritable,
                            warningWhenInactive = true,
                            onClick = onNavigateToSettings
                        )
                        CapabilityPill(
                            label = if (state.permissions.isEffectiveRootReady) "ROOT actif" else "Sans ROOT",
                            icon = Icons.Filled.AdminPanelSettings,
                            active = state.permissions.isEffectiveRootReady,
                            warningWhenInactive = true,
                            onClick = onNavigateToSettings
                        )
                        CapabilityPill(
                            label = if (state.permissions.isForegroundServiceActive) "Service live" else "Service inactif",
                            icon = Icons.Filled.NotificationsActive,
                            active = state.permissions.isForegroundServiceActive,
                            warningWhenInactive = false,
                            onClick = onNavigateToSettings
                        )
                    }

                    if (state.permissions.limitations.isNotEmpty()) {
                        Surface(
                            color = AmberContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, AmberWarning.copy(alpha = 0.35f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Warning,
                                    contentDescription = "Limitations actives",
                                    tint = AmberWarning,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Mode non-bloquant actif : ${state.permissions.limitations.first().title}. La collecte continue avec les capacités disponibles.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Text(
                        text = "Destination export : ${state.exportState.sessionDirectoryPath.ifBlank { state.permissions.activeStorageRootPath }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyanTelemetry,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 4. Reconnaissance Appareil & ROM
        item {
            val p = state.deviceProfile
            ForensicCard(
                modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Fingerprint,
                            contentDescription = null,
                            tint = CyanTelemetry
                        )
                        Text(
                            text = "${p.manufacturer} ${p.model}".trim().ifBlank { "Appareil Android" },
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Text(
                        text = p.romType.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldVerified
                    )
                    Text(
                        text = "Android ${p.androidRelease} (API ${p.sdkInt}) • SoC ${p.socModel} • SELinux: ${p.selinuxMode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Écran: ${p.displayResolution} @ ${p.displayRefreshRateHz.toInt()} Hz • Biométrie: ${p.biometricStatusLabel}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (p.romEvidence.isNotEmpty()) {
                        Text(
                            text = "Indice ROM : ${p.romEvidence.first()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = EmeraldVerified,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Observation passive stricte : aucune modification système ni lecture biométrique protégée.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 5. Flux chronologique des événements récents
        item {
            Row(
                modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Journal d'observation en direct",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${state.events.size} entrée(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (state.events.isEmpty()) {
            item {
                ForensicCard(modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)) {
                    Text(
                        text = "En attente d'événements... Lancez l'enregistrement d'une empreinte dans les Paramètres Android.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(state.events.reversed().take(20), key = { it.id }) { evt ->
                EventRowCard(
                    event = evt,
                    onClick = { onInspectEvent(evt) },
                    modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)
                )
            }
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SlateSurfaceElevated,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, SlateOutline),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = CyanTelemetry
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun EventRowCard(
    event: FodEvent,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(
            1.dp,
            if (event.triggersInvestigation) CyanTelemetry.copy(alpha = 0.5f) else SlateOutline
        ),
        modifier = modifier.clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${event.timestampIso.substringAfter("T").removeSuffix("Z")} • ${event.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanTelemetry
                )
                EvidenceBadge(level = event.evidenceLevel)
            }
            Text(
                text = event.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = event.rawPayload,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
