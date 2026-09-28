package it.dvgarage.obd.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import it.dvgarage.obd.Settings

class ObdService : Service() {
    private var runner: ObdRunner? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (runner == null) {
            val config = Settings(this).load()
            if (!config.isComplete) {
                AppState.update(Phase.ERROR, "Configuration incomplete")
                stopSelf()
                return START_NOT_STICKY
            }
            runner = ObdRunner(this, config).also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runner?.stop()
        runner = null
        super.onDestroy()
    }

    private fun startInForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OBD connection", NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("DvGarage OBD")
            .setContentText("Connected to the vehicle adapter and the server")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "obd"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "it.dvgarage.obd.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ObdService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ObdService::class.java).setAction(ACTION_STOP))
        }
    }
}
