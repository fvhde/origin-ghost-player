package com.originghostplayer.android

import android.app.NotificationManager
import android.content.Context
import android.util.Log

/**
 * Ported from OrangeBox-src's OriginIslandBuilder.grantScenes(): invokes the hidden
 * NotificationManager.setSuperXInfosSceneList(List, List, List, List) to whitelist this
 * (spoofed) package for every known SuperX scene. Proven to work for the AMap-spoofed app's
 * NAVIGATION scene — testing here whether OriginPlayer's native media treatment is actually
 * gated by this same island whitelist, rather than being an independent system.
 */
object SuperXGrant {

    private const val TAG = "SuperXGrant"

    private val SUPERX_SCENES = listOf(
        "NAVIGATION", "MOVIE", "HEALTH_REGISTER", "TAXI", "TAKEOUT", "DELIEVERY",
        "CAR_STATE", "METTING", "TRAIN", "FLIGHT", "INCALLING", "VOIPCALL",
        "TIMER", "RIDE_GUIDE", "CRITICAL",
    )

    fun grant(context: Context) {
        Log.e(TAG, "grant() called for ${context.packageName}")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm == null) {
            Log.e(TAG, "NotificationManager unavailable")
            return
        }
        try {
            val method = NotificationManager::class.java.getMethod(
                "setSuperXInfosSceneList",
                List::class.java, List::class.java, List::class.java, List::class.java,
            )
            Log.e(TAG, "method resolved: $method")
            val scenes = ArrayList(SUPERX_SCENES)
            val enabled = ArrayList<String>()
            val pkgs = ArrayList<String>()
            val allowed = ArrayList<String>()
            repeat(scenes.size) {
                enabled.add("true")
                pkgs.add(context.packageName)
                allowed.add("true")
            }
            Log.e(TAG, "about to invoke")
            method.invoke(nm, scenes, enabled, pkgs, allowed)
            Log.e(TAG, "setSuperXInfosSceneList granted for ${context.packageName}")
        } catch (t: Throwable) {
            Log.e(TAG, "setSuperXInfosSceneList unavailable", t)
        }
    }
}
