package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.UserSettings
import com.example.engine.FodEngineState
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonBreak
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldVerified
import com.example.ui.theme.SlateOutline

@Composable
fun SettingsScreen(
    state: FodEngineState,
    userSettings: UserSettings,
    onRequestStoragePermission: () -> Unit,
    onSyncToSdcardFod: () -> Unit,
    onToggleRoot: (Boolean) -> Unit,
    onRunRootTest: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onToggleAutoStart: (Boolean) -> Unit,
    onSelectPollingInterval: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val perms = state.permissions
    val rootDiag = perms.rootDiagnostic

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 1. Stockage /sdcard/FOD/
        item {
            ForensicCard(
                modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp),
                borderColor = if (perms.sdcardFodWritable) EmeraldVerified.copy(alpha = 0.55f) else AmberWarning.copy(alpha = 0.55f)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = if (perms.sdcardFodWritable) EmeraldVerified else AmberWarning
                        )
                        Text(
                            text = "Accès au stockage (/sdcard/FOD/)",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Text(
                        text = if (perms.sdcardFodWritable) {
                            "Autorisation accordée : les rapports JSON, événements et preuves sont enregistrés directement dans /sdcard/FOD/."
                        } else {
                            "Autorisation /sdcard/FOD/ absente : le moteur ne bloque pas et sauvegarde en zone de repli sécurisée."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Text(
                        text = "Chemin actif : ${perms.activeStorageRootPath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyanTelemetry
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onRequestStoragePermission,
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("grant_storage_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(if (perms.sdcardFodWritable) "Vérifier accès" else "Autoriser /sdcard/FOD/")
                        }

                        if (state.exportState.pendingSyncToSdcard) {
                            OutlinedButton(
                                onClick = onSyncToSdcardFod,
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .testTag("sync_sdcard_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Synchroniser")
                            }
                        }
                    }
                }
            }
        }

        // 2. Privilèges ROOT & Test réel
        item {
            ForensicCard(
                modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp),
                borderColor = if (perms.isEffectiveRootReady) EmeraldVerified.copy(alpha = 0.55f) else SlateOutline
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AdminPanelSettings,
                                contentDescription = null,
                                tint = if (perms.isEffectiveRootReady) EmeraldVerified else CyanTelemetry
                            )
                            Column {
                                Text(
                                    text = "Privilèges ROOT (lecture seule)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = "Débloque logcat système, dumpsys, lshal, /vendor & /data/adb",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = userSettings.rootEnabled,
                            onCheckedChange = onToggleRoot,
                            modifier = Modifier.testTag("root_toggle_switch")
                        )
                    }

                    Button(
                        onClick = onRunRootTest,
                        enabled = !state.isBusyAction,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (perms.isEffectiveRootReady) EmeraldVerified else CyanTelemetry,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("test_root_button")
                    ) {
                        Icon(Icons.Filled.Terminal, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Tester l'accès ROOT réel (su -c id)")
                    }

                    // Console factuelle de diagnostic ROOT
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, SlateOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "État vérifié : ${
                                    when {
                                        rootDiag.isRootAvailable -> "ACCÈS ROOT CONFIRMÉ (uid=0)"
                                        rootDiag.hasBeenTested -> "ROOT NON DISPONIBLE / REFUSÉ"
                                        else -> "NON TESTÉ"
                                    }
                                }",
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    rootDiag.isRootAvailable -> EmeraldVerified
                                    rootDiag.hasBeenTested -> AmberWarning
                                    else -> CyanTelemetry
                                }
                            )
                            Text(
                                text = "Sortie id : ${rootDiag.suUidOutput.ifBlank { "—" }}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "Gestionnaire : ${rootDiag.detectedManager} | SELinux : ${rootDiag.selinuxMode}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (rootDiag.suBinaryPaths.isNotEmpty()) {
                                Text(
                                    text = "Binaires su : ${rootDiag.suBinaryPaths.joinToString(", ")}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CyanTelemetry
                                )
                            }
                            if (rootDiag.errorDetail.isNotBlank()) {
                                Text(
                                    text = "Détail : ${rootDiag.errorDetail}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = CrimsonBreak
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3. Surveillance en arrière-plan & Notifications
        item {
            ForensicCard(modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = null,
                            tint = CyanTelemetry
                        )
                        Text(
                            text = "Service de premier plan & Collecte",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Activation auto au lancement", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Capture l'état initial et active la surveillance dès l'ouverture.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = userSettings.autoStartMonitoring,
                            onCheckedChange = onToggleAutoStart,
                            modifier = Modifier.testTag("autostart_toggle_switch")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (perms.notificationsGranted) "Notification persistante autorisée" else "Permission notification requise",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = if (perms.isForegroundServiceActive) "Service actif en arrière-plan" else "Service en veille",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (perms.isForegroundServiceActive) EmeraldVerified else AmberWarning
                            )
                        }
                        if (!perms.notificationsGranted) {
                            OutlinedButton(
                                onClick = onRequestNotificationPermission,
                                modifier = Modifier.minimumInteractiveComponentSize()
                            ) {
                                Text("Autoriser")
                            }
                        }
                    }

                    Text(
                        text = "Cadence d'échantillonnage passif",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(1000L to "1.0 s", 1500L to "1.5 s", 2500L to "2.5 s").forEach { (ms, label) ->
                            FilterChip(
                                selected = userSettings.pollingIntervalMs == ms,
                                onClick = { onSelectPollingInterval(ms) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }
        }

        // 4. Limitations actives & Garanties de sécurité
        item {
            ForensicCard(modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Security, contentDescription = null, tint = EmeraldVerified)
                        Text("Garanties de sécurité passive", style = MaterialTheme.typography.titleMedium)
                    }
                    Surface(
                        color = EmeraldContainer.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("• Lecture seule stricte : aucune écriture sur /system, /vendor, /odm, /sys ou /proc.", style = MaterialTheme.typography.bodyMedium)
                            Text("• Zéro modification des configurations FOD ou des modules existants.", style = MaterialTheme.typography.bodyMedium)
                            Text("• Aucune extraction des modèles biométriques protégés (/data/vendor_de).", style = MaterialTheme.typography.bodyMedium)
                            Text("• Zéro supposition : tout fait non observable est classé en limitation explicite.", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    if (perms.limitations.isNotEmpty()) {
                        Text(
                            text = "Limitations actuellement signalées",
                            style = MaterialTheme.typography.titleSmall,
                            color = AmberWarning
                        )
                        perms.limitations.forEach { lim ->
                            Surface(
                                color = AmberContainer.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(lim.title, style = MaterialTheme.typography.titleSmall, color = AmberWarning)
                                    Text(lim.impact, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
