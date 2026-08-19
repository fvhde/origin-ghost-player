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
 *
 * Also self-reschedules a periodic health-check alarm independent of task removal — a long
 * background stretch (observed: another app holding audio output for ~10 minutes) can get this
 * process killed under memory pressure without the task ever being removed, so onTaskRemoved's
 * restart alarm never engages. The health check re-arms itself every run, so even a silent kill
 * gets caught at the next scheduled tick instead of staying dead until the user reopens the app.
 */
class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "keep_alive"
        private const val FGS_ID = 1
        private const val RESTART_REQUEST = 7001
        private const val HEALTH_CHECK_REQUEST = 7002
        private const val HEALTH_CHECK_INTERVAL_MS = 5 * 60 * 1000L
        const val ACTION_KEEPALIVE = "ACTION_KEEPALIVE"
        const val ACTION_HEALTH_CHECK = "ACTION_HEALTH_CHECK"

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
        scheduleHealthCheck()
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

    /** Re-armed on every start, including a health-check-triggered one — a self-perpetuating
     *  chain that survives this process dying, since the PendingIntent still fires and restarts
     *  the service even if nothing is alive to have rescheduled it in the meantime. */
    private fun scheduleHealthCheck() {
        runCatching {
            val check = PendingIntent.getService(
                this, HEALTH_CHECK_REQUEST,
                Intent(this, KeepAliveService::class.java).setAction(ACTION_HEALTH_CHECK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            getSystemService(AlarmManager::class.java)?.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + HEALTH_CHECK_INTERVAL_MS, check,
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
