package com.originghostplayer.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat

fun isListenerEnabled(context: android.content.Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

class MainActivity : Activity() {

    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // OriginPlayer's native tap handler always opens the app owning the session identity
        // (us), ignoring MediaSession.sessionActivity — so redirect to whatever's actually
        // playing instead of showing our own UI. Only when something IS playing: that's exactly
        // when OriginPlayer's widget (and thus this redirect) is reachable at all, so a normal
        // launcher tap while nothing plays still reaches the real UI below.
        val playingPackage = MediaProbeListener.instance?.currentController()?.packageName
        if (playingPackage != null) {
            val redirect = packageManager.getLaunchIntentForPackage(playingPackage)
            if (redirect != null) {
                startActivity(redirect)
                finish()
                return
            }
        }

        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.status_text)
        findViewById<Button>(R.id.open_settings_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        // Grant on every app open too, not just when the listener (re)connects — cheap and
        // matches OrangeBox-src's OriginIsleApp.onCreate() pattern.
        SuperXGrant.grant(applicationContext)
        RebindHelper.ensureEnabled(applicationContext)
        KeepAliveService.start(this)
    }

    override fun onResume() {
        super.onResume()
        RebindHelper.forceRebindIfNeeded(applicationContext)
        statusText.text = if (isListenerEnabled(this)) {
            getString(R.string.status_granted)
        } else {
            getString(R.string.status_not_granted)
        }
    }
}
