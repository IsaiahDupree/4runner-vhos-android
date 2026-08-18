package dev.vhos.headunit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import dev.vhos.ble.DualGatewayManager
import dev.vhos.store.EvidenceDatabase

class VehicleSessionService : Service() {
    private lateinit var database: EvidenceDatabase
    private lateinit var gateways: DualGatewayManager
    private var gatewaysClosed = false

    override fun onCreate() {
        super.onCreate()
        database = EvidenceDatabase(this)
        gateways = DualGatewayManager(this, database) { snapshot ->
            HeadUnitRuntime.updateDevice(snapshot)
            val counts = database.counts()
            HeadUnitRuntime.updateCounts(counts.logicalFrames, counts.canObservations)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(snapshot.detail))
        }
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, notification("Starting service-filtered gateway discovery…"))
                HeadUnitRuntime.setRunning(true, "Starting vehicle session.")
                gatewaysClosed = false
                gateways.start()
            }
            ACTION_STOP -> {
                gateways.stop()
                gatewaysClosed = true
                HeadUnitRuntime.setRunning(false, "Vehicle session stopped.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_RELEASE -> {
                gateways.releaseForIPhone()
                gatewaysClosed = true
                HeadUnitRuntime.setRunning(false, "Gateway released for iPhone.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (!gatewaysClosed) gateways.stop()
        database.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "VHOS vehicle connection",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Visible status for an owner-started BLE vehicle evidence session."
                setShowBadge(false)
            }
        )
    }

    private fun notification(detail: String): Notification {
        val activityIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("4Runner Vehicle Health OS")
            .setContentText(detail)
            .setContentIntent(activityIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val ACTION_START = "dev.vhos.headunit.action.START"
        const val ACTION_STOP = "dev.vhos.headunit.action.STOP"
        const val ACTION_RELEASE = "dev.vhos.headunit.action.RELEASE"
        private const val CHANNEL_ID = "vhos_vehicle_connection"
        private const val NOTIFICATION_ID = 4101
    }
}
