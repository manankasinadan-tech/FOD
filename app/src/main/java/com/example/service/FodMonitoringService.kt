package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.engine.FodEngineController
import com.example.model.EnginePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FodMonitoringService : Service() {

    companion object {
        const val ACTION_START = "com.example.action.START_FOD_MONITORING"
        const val ACTION_STOP = "com.example.action.STOP_FOD_MONITORING"
        const val ACTION_FINALIZE = "com.example.action.FINALIZE_FOD"
        private const val NOTIFICATION_ID = 4107
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_FINALIZE -> {
                serviceScope.launch {
                    FodEngineController.finalizeAndReconstruct(applicationContext)
                }
            }
        }

        val initialNotif = buildNotification(
            title = getString(R.string.notif_title_monitoring),
            content = "En attente d'un événement d'enregistrement d'empreinte..."
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, initialNotif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, initialNotif)
        }

        if (observerJob?.isActive != true) {
            observerJob = serviceScope.launch {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                FodEngineController.state.collectLatest { st ->
                    val title = when (st.phase) {
                        EnginePhase.INVESTIGATING -> getString(R.string.notif_title_investigating)
                        EnginePhase.RECONSTRUCTED -> "FOD Detector — Reconstruction terminée"
                        else -> getString(R.string.notif_title_monitoring)
                    }
                    val detail = "${st.events.size} événement(s) • ${st.components.size} composant(s) • ${
                        if (st.permissions.isEffectiveRootReady) "ROOT actif" else "Mode standard"
                    }"
                    nm.notify(NOTIFICATION_ID, buildNotification(title, detail))
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        observerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = getString(R.string.notif_channel_id)
            if (nm.getNotificationChannel(channelId) == null) {
                val channel = NotificationChannel(
                    channelId,
                    getString(R.string.notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notif_channel_desc)
                    setShowBadge(false)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, FodMonitoringService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val finalizeIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, FodMonitoringService::class.java).apply { action = ACTION_FINALIZE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_reconstruct), finalizeIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }
}
