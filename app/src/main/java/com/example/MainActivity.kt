package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.FodAppShell
import com.example.ui.theme.FodDetectorTheme
import com.example.viewmodel.FodDetectorViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: FodDetectorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

        setContent {
            FodDetectorTheme {
                val engineState by viewModel.engineState.collectAsStateWithLifecycle()
                val userSettings by viewModel.userSettings.collectAsStateWithLifecycle()
                val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
                val toastMessage by viewModel.toastMessage.collectAsStateWithLifecycle()

                val legacyStorageLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) {
                    viewModel.onAppResumed()
                }

                val notifPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) {
                    viewModel.onAppResumed()
                }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.onAppResumed()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                val requestStorageAccess: () -> Unit = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        runCatching { startActivity(intent) }.onFailure {
                            runCatching { startActivity(fallback) }
                        }
                    } else {
                        legacyStorageLauncher.launch(
                            arrayOf(
                                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                Manifest.permission.READ_EXTERNAL_STORAGE
                            )
                        )
                    }
                }

                val requestNotificationAccess: () -> Unit = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                val openFingerprintSettings: () -> Unit = {
                    val (intent, actionName) = viewModel.buildFingerprintEnrollIntent()
                    viewModel.notifySettingsEnrollLaunched(actionName)
                    runCatching { startActivity(intent) }.onFailure {
                        runCatching {
                            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }

                FodAppShell(
                    currentTab = currentTab,
                    engineState = engineState,
                    userSettings = userSettings,
                    toastMessage = toastMessage,
                    onClearToast = viewModel::clearToast,
                    onSelectTab = viewModel::selectTab,
                    onOpenFingerprintSettings = openFingerprintSettings,
                    onToggleMonitoring = viewModel::toggleMonitoring,
                    onFinalizeReconstruction = viewModel::finalizeReconstruction,
                    onStartNewSession = viewModel::startNewSession,
                    onRequestStoragePermission = requestStorageAccess,
                    onSyncToSdcardFod = viewModel::syncToSdcardFod,
                    onToggleRoot = viewModel::setRootEnabled,
                    onRunRootTest = viewModel::runRootTest,
                    onRequestNotificationPermission = requestNotificationAccess,
                    onToggleAutoStart = viewModel::setAutoStartMonitoring,
                    onSelectPollingInterval = viewModel::setPollingInterval
                )
            }
        }
    }
}
