package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.UserSettings
import com.example.engine.FodEngineState
import com.example.model.EvidenceLevel
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.SlateSurface
import com.example.viewmodel.MainTab

private data class NavItemSpec(
    val tab: MainTab,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val navItems = listOf(
    NavItemSpec(MainTab.COCKPIT, R.string.nav_cockpit, Icons.Filled.Radar, Icons.Outlined.Radar, "nav_cockpit"),
    NavItemSpec(MainTab.MECHANISM, R.string.nav_mechanism, Icons.Filled.AccountTree, Icons.Outlined.AccountTree, "nav_mechanism"),
    NavItemSpec(MainTab.DISCOVERIES, R.string.nav_discoveries, Icons.Filled.BugReport, Icons.Outlined.BugReport, "nav_discoveries"),
    NavItemSpec(MainTab.SETTINGS, R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
)

@Composable
fun FodAppShell(
    currentTab: MainTab,
    engineState: FodEngineState,
    userSettings: UserSettings,
    toastMessage: String?,
    onClearToast: () -> Unit,
    onSelectTab: (MainTab) -> Unit,
    onOpenFingerprintSettings: () -> Unit,
    onToggleMonitoring: () -> Unit,
    onFinalizeReconstruction: () -> Unit,
    onStartNewSession: () -> Unit,
    onRequestStoragePermission: () -> Unit,
    onSyncToSdcardFod: () -> Unit,
    onToggleRoot: (Boolean) -> Unit,
    onRunRootTest: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onToggleAutoStart: (Boolean) -> Unit,
    onSelectPollingInterval: (Long) -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var inspectedProof by remember { mutableStateOf<InspectedProof?>(null) }

    LaunchedEffect(toastMessage) {
        if (!toastMessage.isNullOrBlank()) {
            snackbarHostState.showSnackbar(toastMessage)
            onClearToast()
        }
    }

    if (currentTab != MainTab.COCKPIT) {
        BackHandler {
            onSelectTab(MainTab.COCKPIT)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            containerColor = ObsidianBg,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar(
                        containerColor = SlateSurface,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        navItems.forEach { item ->
                            val selected = currentTab == item.tab
                            val label = stringResource(item.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { onSelectTab(item.tab) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label, maxLines = 1) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail(
                        containerColor = SlateSurface,
                        modifier = Modifier.fillMaxHeight()
                    ) {
                        navItems.forEach { item ->
                            val selected = currentTab == item.tab
                            val label = stringResource(item.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { onSelectTab(item.tab) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label, maxLines = 1) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when (currentTab) {
                        MainTab.COCKPIT -> CockpitScreen(
                            state = engineState,
                            onOpenFingerprintSettings = onOpenFingerprintSettings,
                            onToggleMonitoring = onToggleMonitoring,
                            onFinalizeReconstruction = onFinalizeReconstruction,
                            onStartNewSession = onStartNewSession,
                            onNavigateToSettings = { onSelectTab(MainTab.SETTINGS) },
                            onNavigateToMechanism = { onSelectTab(MainTab.MECHANISM) },
                            onInspectEvent = { evt ->
                                inspectedProof = InspectedProof(
                                    title = evt.title,
                                    subtitle = "${evt.timestampIso} • ${evt.source} (${evt.category})",
                                    level = evt.evidenceLevel,
                                    rawLines = listOf(evt.rawPayload)
                                )
                            }
                        )

                        MainTab.MECHANISM -> MechanismScreen(
                            state = engineState,
                            onInspectStage = { stage ->
                                inspectedProof = InspectedProof(
                                    title = stage.layer.label,
                                    subtitle = "${stage.status.label} • ${stage.componentsCount} composant(s)",
                                    level = when (stage.status) {
                                        com.example.model.StageStatus.VERIFIED_ACTIVE -> EvidenceLevel.FAIT_VERIFIE
                                        com.example.model.StageStatus.CORRELATED -> EvidenceLevel.CORRELATION_OBSERVEE
                                        else -> EvidenceLevel.NON_OBSERVE_LIMITATION
                                    },
                                    rawLines = stage.rawProofs.ifEmpty { listOf(stage.factualSummary) }
                                )
                            }
                        )

                        MainTab.DISCOVERIES -> DiscoveriesScreen(
                            state = engineState,
                            onInspectProof = { inspectedProof = it }
                        )

                        MainTab.SETTINGS -> SettingsScreen(
                            state = engineState,
                            userSettings = userSettings,
                            onRequestStoragePermission = onRequestStoragePermission,
                            onSyncToSdcardFod = onSyncToSdcardFod,
                            onToggleRoot = onToggleRoot,
                            onRunRootTest = onRunRootTest,
                            onRequestNotificationPermission = onRequestNotificationPermission,
                            onToggleAutoStart = onToggleAutoStart,
                            onSelectPollingInterval = onSelectPollingInterval
                        )
                    }
                }
            }
        }
    }

    inspectedProof?.let { proof ->
        ProofDetailBottomSheet(
            proof = proof,
            onDismiss = { inspectedProof = null }
        )
    }
}
