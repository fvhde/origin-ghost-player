package com.originghostplayer.android

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * Ported from OrangeBox-src's NotificationCastListener.forceRebind()/OriginIsleApp.ensureEnabled():
 * plain requestRebind() is a no-op after a process kill (it only works on a listener explicitly
 * snoozed via requestUnbind, which a kill never does). Toggling the component's enabled state
 * fires ACTION_PACKAGE_CHANGED, which does force a rebind. We hit this exact issue testing the
 * probe after `am force-stop` — the listener stayed unbound until manually toggled.
 */
object RebindHelper {

    private const val REBIND_THROTTLE_MS = 10_000L

    @Volatile
    private var lastRebindAt = 0L

    private fun component(context: Context) = ComponentName(context, MediaProbeListener::class.java)

    /** Call periodically (app open, keep-alive service tick) — cheap no-op while connected. */
    fun forceRebindIfNeeded(context: Context) {
        if (MediaProbeListener.instance != null) return
        if (!NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) return
        val now = System.currentTimeMillis()
        if (now - lastRebindAt < REBIND_THROTTLE_MS) return

        val pm = context.packageManager
        val comp = component(context)
        val disabled = runCatching {
            pm.setComponentEnabledSetting(comp, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }.isSuccess
        val enabled = runCatching {
            pm.setComponentEnabledSetting(comp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        }.isSuccess
        if (disabled && enabled) {
            lastRebindAt = now
            runCatching { NotificationListenerService.requestRebind(comp) }
        }
    }

    /** Undo a forceRebind that only got halfway (process died between the two toggle calls). */
    fun ensureEnabled(context: Context) {
        val pm = context.packageManager
        val comp = component(context)
        runCatching {
            if (pm.getComponentEnabledSetting(comp) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
                pm.setComponentEnabledSetting(comp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
