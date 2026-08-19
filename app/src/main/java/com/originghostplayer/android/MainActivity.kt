package com.originghostplayer.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun isListenerEnabled(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

fun isBatteryUnrestricted(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java)
    return pm?.isIgnoringBatteryOptimizations(context.packageName) == true
}

fun requestIgnoreBattery(context: Context) {
    if (isBatteryUnrestricted(context)) return
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var batteryStatusText: TextView

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
        // targetSdk 36 enforces edge-to-edge by default — without this the top text draws behind
        // the status bar. Pad the scroll container by the system bar insets instead.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_scroll)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        statusText = findViewById(R.id.status_text)
        batteryStatusText = findViewById(R.id.battery_status_text)
        findViewById<Button>(R.id.open_settings_button).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.allow_background_button).setOnClickListener {
            requestIgnoreBattery(this)
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
        batteryStatusText.text = if (isBatteryUnrestricted(this)) {
            getString(R.string.battery_granted)
        } else {
            getString(R.string.battery_not_granted)
        }
    }
}
