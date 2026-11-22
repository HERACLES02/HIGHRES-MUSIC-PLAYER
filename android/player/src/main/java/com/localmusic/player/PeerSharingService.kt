package com.localmusic.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.util.concurrent.TimeUnit

class PeerSharingService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val tab get() = (application as MusicApplication).soulseekTab

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Soulseek sharing", NotificationManager.IMPORTANCE_LOW))
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Soulseek sharing over Wi-Fi")
            .setContentText("Open Soulseek to manage sharing.")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
            .apply {
                if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }
            .build()
        startForeground(NOTIFICATION_ID, notification)
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LocalMusic:PeerSharing")
                .apply { acquire(TimeUnit.HOURS.toMillis(6)) }
        }
        tab?.sharingServiceStarted()
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        tab?.sharingServiceEnded()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "peer-sharing"
        const val NOTIFICATION_ID = 45418
    }
}
