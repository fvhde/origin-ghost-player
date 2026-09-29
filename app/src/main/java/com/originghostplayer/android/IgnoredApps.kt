package com.originghostplayer.android

import android.content.Context
import android.content.SharedPreferences

/**
 * Apps the user never wants mirrored. They keep playing normally — [MediaProbeListener] just
 * never tracks their sessions, so they never reach OriginPlayer. Stored in SharedPreferences,
 * which the listener and the activities share since they all run in the same process.
 */
object IgnoredApps {
    private const val PREFS = "ignored_apps"
    private const val KEY_PACKAGES = "packages"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_PACKAGES, emptySet()).orEmpty()

    fun isIgnored(context: Context, pkg: String): Boolean = pkg in get(context)

    fun setIgnored(context: Context, pkg: String, ignored: Boolean) {
        val next = get(context).toMutableSet()
        if (ignored) next.add(pkg) else next.remove(pkg)
        // Always write a fresh set — mutating the instance getStringSet() returned is unsupported.
        prefs(context).edit().putStringSet(KEY_PACKAGES, next).apply()
        // Drop (or pick back up) the affected app right away instead of on the next poll tick.
        MediaProbeListener.instance?.retrack()
    }
}

/**
 * When each app last played media, so the ignored-apps page can list those first. Recorded by
 * [MediaProbeListener] for every playing session it sees — ignored ones included, so they stay
 * in that section instead of vanishing from it the moment they're ignored.
 */
object MediaHistory {
    private const val PREFS = "media_history"

    /** The listener sees a playing app on every 1s poll tick; only persist every so often. */
    private const val WRITE_INTERVAL_MS = 60_000L

    private val lastWritten = HashMap<String, Long>()

    fun notePlaying(context: Context, pkg: String) {
        val now = System.currentTimeMillis()
        synchronized(lastWritten) {
            val last = lastWritten[pkg]
            if (last != null && now - last < WRITE_INTERVAL_MS) return
            lastWritten[pkg] = now
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(pkg, now).apply()
    }

    /** Package → last-played wall-clock millis. */
    fun all(context: Context): Map<String, Long> =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).all
            .mapNotNull { (pkg, time) -> (time as? Long)?.let { pkg to it } }
            .toMap()
}
