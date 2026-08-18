package com.originghostplayer.android

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Ported from OrangeBox-src's PlaygroundService keep-alive recipe: an always-on foreground
 * service (stopWithTask=false in the manifest) that re-arms a restart alarm on task removal, so
 * swiping the app away from recents doesn't kill the identity bridge. The notification channel
 * is IMPORTANCE_NONE, so there's no visible icon for it.
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val FGS_ID = 1
        private const val RESTART_REQUEST = 7001
        const val ACTION_KEEPALIVE = "ACTION_KEEPALIVE"

        fun start(context: Context) {
            context.startService(Intent(context, KeepAliveService::class.java))
        }
    }

    private var isForegroundActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        SuperXGrant.grant(applicationContext)
        RebindHelper.ensureEnabled(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        RebindHelper.forceRebindIfNeeded(applicationContext)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        runCatching { ensureForeground() }
        runCatching {
            val restart = PendingIntent.getService(
                this, RESTART_REQUEST,
                Intent(this, KeepAliveService::class.java).setAction(ACTION_KEEPALIVE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            getSystemService(AlarmManager::class.java)?.set(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000L, restart,
            )
        }
    }

    private fun ensureForeground() {
        if (isForegroundActive) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_NONE),
            )
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
        isForegroundActive = runCatching {
            startForeground(FGS_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }.recoverCatching {
            startForeground(FGS_ID, notification)
        }.isSuccess
        if (!isForegroundActive) stopSelf()
    }
}
