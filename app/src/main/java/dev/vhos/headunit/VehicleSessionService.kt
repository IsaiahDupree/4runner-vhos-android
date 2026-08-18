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
    @Volatile private var gateways: DualGatewayManager? = null
    @Volatile private var requestedAction: String = ACTION_STOP
    @Volatile private var initializing = false
    @Volatile private var destroyed = false
    @Volatile private var gatewaysClosed = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> {
                requestedAction = ACTION_START
                startForeground(NOTIFICATION_ID, notification("Opening encrypted evidence store…"))
                HeadUnitRuntime.setRunning(true, "Verifying encrypted evidence store before BLE discovery.")
                initializeAndStart()
            }
            ACTION_STOP -> {
                requestedAction = ACTION_STOP
                gateways?.stop()
                gatewaysClosed = true
                HeadUnitRuntime.setRunning(false, "Vehicle session stopped.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_RELEASE -> {
                requestedAction = ACTION_RELEASE
                gateways?.releaseForIPhone()
                gatewaysClosed = true
                HeadUnitRuntime.setRunning(false, "Gateway released for iPhone.")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        if (!gatewaysClosed) gateways?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun initializeAndStart() {
        if (gateways != null) {
            gatewaysClosed = false
            gateways?.start()
            return
        }
        synchronized(this) {
            if (initializing) return
            initializing = true
        }
        Thread {
            try {
                val database = EvidenceDatabase.open(applicationContext)
                if (destroyed || requestedAction != ACTION_START) return@Thread
                val manager = DualGatewayManager(this, database) { snapshot ->
                    HeadUnitRuntime.updateDevice(snapshot)
                    val counts = database.counts()
                    HeadUnitRuntime.updateCounts(counts.logicalFrames, counts.canObservations)
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        notification(snapshot.detail),
                    )
                }
                synchronized(this) {
                    if (destroyed || requestedAction != ACTION_START) return@synchronized
                    gateways = manager
                    gatewaysClosed = false
                }
                if (gateways === manager && !destroyed && requestedAction == ACTION_START) {
                    HeadUnitRuntime.setRunning(true, "Encrypted evidence store verified; starting BLE discovery.")
                    manager.start()
                }
            } catch (error: Exception) {
                val detail = "Encrypted evidence store blocked vehicle session: " +
                    (error.message ?: error.javaClass.simpleName)
                HeadUnitRuntime.setRunning(false, detail)
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    notification(detail),
                )
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } finally {
                initializing = false
            }
        }.start()
    }

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
