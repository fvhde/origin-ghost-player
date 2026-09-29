package com.originghostplayer.android

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ImageView
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

    companion object {
        // vivo system surfaces confirmed (via Activity.getReferrer()) to tap-through to us —
        // each ignores MediaSession.setSessionActivity() and always opens the app owning the
        // session identity (us), so this is the workaround. A direct launcher-icon tap instead
        // carries the launcher's own package (e.g. com.bbk.launcher2) and is excluded on purpose.
        private val REDIRECT_SOURCE_PACKAGES = setOf(
            "com.vivo.musicwidgetmix", // Control Panel's OriginPlayer MusicCard
            "com.vivo.systemuiplugin", // status bar tap surface
            "com.android.systemui", // lockscreen media widget tap surface
        )

        // The home-screen/AOD OriginPlayer widget launches us with a plain MAIN/LAUNCHER intent
        // whose referrer is com.bbk.launcher2 — indistinguishable from a direct icon tap by
        // referrer alone — but tags the intent with this extra (confirmed via logcat on-device).
        private const val LAUNCH_FROM_EXTRA_KEY = "key_launch_from"
        private val REDIRECT_LAUNCH_FROM_VALUES = setOf("vivomusicmix")

        private const val PREVIEW_REFRESH_MS = 1000L
    }

    private lateinit var nowPlayingIsland: View
    private lateinit var nowPlayingArt: ImageView
    private lateinit var nowPlayingTitle: TextView
    private lateinit var nowPlayingApp: TextView
    private lateinit var nowPlayingBars: View
    private lateinit var nowPlayingHint: TextView
    private lateinit var statusText: TextView
    private lateinit var batteryStatusText: TextView
    private lateinit var ignoredAppsSummary: TextView
    private lateinit var ignoredAppsCount: TextView

    /** Keeps the live preview in step with the listener's 1s poll while we're on screen. */
    private val previewHandler = Handler(Looper.getMainLooper())
    private val previewRunnable = object : Runnable {
        override fun run() {
            refreshNowPlaying()
            previewHandler.postDelayed(this, PREVIEW_REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(R.layout.activity_main)
        // targetSdk 36 enforces edge-to-edge by default — without this the top text draws behind
        // the status bar. Pad the scroll container by the system bar insets instead.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root_scroll)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        nowPlayingIsland = findViewById(R.id.now_playing_island)
        nowPlayingArt = findViewById(R.id.now_playing_art)
        nowPlayingArt.clipToOutline = true
        nowPlayingTitle = findViewById(R.id.now_playing_title)
        nowPlayingApp = findViewById(R.id.now_playing_app)
        nowPlayingBars = findViewById(R.id.now_playing_bars)
        nowPlayingHint = findViewById(R.id.now_playing_hint)
        statusText = findViewById(R.id.status_text)
        batteryStatusText = findViewById(R.id.battery_status_text)
        ignoredAppsSummary = findViewById(R.id.ignored_apps_summary)
        ignoredAppsCount = findViewById(R.id.ignored_apps_count)
        findViewById<View>(R.id.notification_access_row).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<View>(R.id.background_running_row).setOnClickListener {
            requestIgnoreBattery(this)
        }
        findViewById<View>(R.id.ignored_apps_row).setOnClickListener {
            startActivity(Intent(this, IgnoredAppsActivity::class.java))
        }
        // Grant on every app open too, not just when the listener (re)connects — cheap and
        // matches OrangeBox-src's OriginIsleApp.onCreate() pattern.
        SuperXGrant.grant(applicationContext)
        RebindHelper.ensureEnabled(applicationContext)
        KeepAliveService.start(this)

        handleLaunch()
    }

    // singleTask (see manifest) means a second launch reuses this instance and lands here
    // instead of a fresh onCreate — without this override the referrer-based redirect below
    // would only ever be checked on a cold start, not the far more common "already running"
    // case, which was the root of "sometimes doesn't redirect from OriginPlayer."
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch()
    }

    override fun onResume() {
        super.onResume()
        RebindHelper.forceRebindIfNeeded(applicationContext)
        refreshStatus()
        previewHandler.postDelayed(previewRunnable, PREVIEW_REFRESH_MS)
    }

    override fun onPause() {
        super.onPause()
        previewHandler.removeCallbacks(previewRunnable)
    }

    /** Only redirect straight to the real playing app when a known vivo system surface launched
     *  us (see REDIRECT_SOURCE_PACKAGES) or the launch intent carries a known widget marker (see
     *  LAUNCH_FROM_EXTRA_KEY — needed because the home-screen widget's referrer is indistinguishable
     *  from a plain launcher-icon tap). A direct launcher-icon tap (or any other launch) always
     *  lands on our own screen instead, with a "now playing" row using the actual controller. */
    private fun handleLaunch() {
        val referrerHost = referrer?.host
        val fromKnownSource = referrerHost in REDIRECT_SOURCE_PACKAGES ||
            intent.getStringExtra(LAUNCH_FROM_EXTRA_KEY) in REDIRECT_LAUNCH_FROM_VALUES
        val controller = MediaProbeListener.instance?.currentController()
        if (fromKnownSource && controller != null) {
            val redirect = packageManager.getLaunchIntentForPackage(controller.packageName)
            if (redirect != null) {
                startActivity(redirect)
                finish()
                return
            }
        }
        refreshStatus()
    }

    private fun refreshStatus() {
        statusText.text = getString(
            if (isListenerEnabled(this)) R.string.state_granted else R.string.state_not_granted,
        )
        batteryStatusText.text = getString(
            if (isBatteryUnrestricted(this)) R.string.state_unrestricted else R.string.state_restricted,
        )

        val ignored = IgnoredApps.get(this).map { appLabelFor(it).toString() }.sortedBy { it.lowercase() }
        ignoredAppsSummary.text = if (ignored.isEmpty()) {
            getString(R.string.ignored_apps_none)
        } else {
            ignored.joinToString(", ")
        }
        ignoredAppsCount.text = ignored.size.toString()
        ignoredAppsCount.visibility = if (ignored.isEmpty()) View.GONE else View.VISIBLE

        refreshNowPlaying()
    }

    /** The live preview: whatever the listener is currently mirroring, tapping through to the
     *  real source app. Ignored apps never show here — the listener never tracks them. */
    private fun refreshNowPlaying() {
        val controller = MediaProbeListener.instance?.currentController()
        if (controller == null) {
            nowPlayingTitle.text = getString(R.string.now_playing_none)
            nowPlayingApp.visibility = View.GONE
            nowPlayingBars.visibility = View.GONE
            nowPlayingHint.visibility = View.GONE
            showArt(null)
            nowPlayingIsland.setOnClickListener(null)
            nowPlayingIsland.isClickable = false
            nowPlayingIsland.contentDescription = getString(R.string.now_playing_none)
            return
        }
        val md = controller.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty().ifBlank { "?" }
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val appLabel = appLabelFor(controller.packageName)
        // Sources without a PlaybackState (Firefox) are mirrored as playing — match that here.
        val playing = controller.playbackState?.state.let { it == null || it == PlaybackState.STATE_PLAYING }

        nowPlayingTitle.text = title
        nowPlayingApp.text = if (artist.isEmpty()) appLabel else "$artist · $appLabel"
        nowPlayingApp.visibility = View.VISIBLE
        nowPlayingBars.visibility = if (playing) View.VISIBLE else View.INVISIBLE
        nowPlayingHint.visibility = View.VISIBLE
        nowPlayingHint.text = getString(R.string.now_playing_hint)
        showArt(
            md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART),
        )
        nowPlayingIsland.contentDescription = getString(R.string.now_playing_description, title, appLabel)
        nowPlayingIsland.setOnClickListener {
            packageManager.getLaunchIntentForPackage(controller.packageName)?.let(::startActivity)
        }
    }

    private fun showArt(art: Bitmap?) {
        if (art != null) {
            nowPlayingArt.setPadding(0, 0, 0, 0)
            nowPlayingArt.setImageBitmap(art)
        } else {
            val pad = (12 * resources.displayMetrics.density).toInt()
            nowPlayingArt.setPadding(pad, pad, pad, pad)
            nowPlayingArt.setImageResource(R.drawable.ic_media_play)
        }
    }

    private fun appLabelFor(pkg: String): CharSequence = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0))
    }.getOrDefault(pkg)
}
