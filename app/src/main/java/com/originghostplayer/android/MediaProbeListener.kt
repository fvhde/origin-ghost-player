package com.originghostplayer.android

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Listens system-wide for other apps' media sessions ONLY, and mirrors whichever one is playing
 * onto this app's OWN MediaSession — under this app's spoofed identity — with NO notification of
 * our own posted at any point. Confirmed working: OriginOS's native OriginPlayer picks this
 * session up regardless of the real source app (tested with Metrolist and Firefox).
 */
class MediaProbeListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: MediaProbeListener? = null

        private const val POLL_INTERVAL_MS = 1000L
    }

    private var mediaSession: MediaSession? = null
    private var trackedController: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null
    private val pollHandler = Handler(Looper.getMainLooper())

    /** Exposed for [MediaButtonReceiver], which has no other way to reach the tracked controller. */
    fun currentController(): MediaController? = trackedController

    private val pollRunnable = object : Runnable {
        override fun run() {
            pickController(activeSessions())?.let(::trackController) ?: clearSession()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            pickController(controllers?.filter { it.packageName != packageName })?.let(::trackController)
                ?: clearSession()
        }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        SuperXGrant.grant(applicationContext)
        mediaSession = MediaSession(this, "MediaProbeSession").apply {
            setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { trackedController?.transportControls?.play() }
                override fun onPause() { trackedController?.transportControls?.pause() }
                override fun onStop() { trackedController?.transportControls?.stop() }
                override fun onSkipToNext() { trackedController?.transportControls?.skipToNext() }
                override fun onSkipToPrevious() { trackedController?.transportControls?.skipToPrevious() }
                override fun onSeekTo(pos: Long) { trackedController?.transportControls?.seekTo(pos) }
            })
            setMediaButtonReceiver(
                PendingIntent.getBroadcast(
                    this@MediaProbeListener, 0,
                    Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this@MediaProbeListener, MediaButtonReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        val component = ComponentName(this, MediaProbeListener::class.java)
        runCatching {
            getSystemService(MediaSessionManager::class.java)
                ?.addOnActiveSessionsChangedListener(sessionsChangedListener, component)
        }
        pickController(activeSessions())?.let(::trackController)
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance === this) instance = null
        pollHandler.removeCallbacks(pollRunnable)
        runCatching {
            getSystemService(MediaSessionManager::class.java)
                ?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        clearSession()
        mediaSession?.release()
        mediaSession = null
        // A system-initiated unbind DOES snooze the component, so unlike the process-kill case a
        // plain requestRebind works here — self-heals transient disconnects without user action.
        runCatching { requestRebind(ComponentName(this, MediaProbeListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val token = sbn.notification.extras.getParcelable(
            NotificationCompat.EXTRA_MEDIA_SESSION,
            MediaSession.Token::class.java,
        ) ?: return
        runCatching { MediaController(this, token) }.getOrNull()?.let(::trackController)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName != trackedController?.packageName) return
        val stillActive = activeSessions()?.any { it.packageName == sbn.packageName } ?: false
        if (!stillActive) clearSession()
    }

    /** Never includes our own MediaProbeSession — without this filter the poll/listener locks
     *  onto our own mirrored session and everything (controls, click target) points at itself. */
    private fun activeSessions(): List<MediaController>? = runCatching {
        getSystemService(MediaSessionManager::class.java)
            ?.getActiveSessions(ComponentName(this, MediaProbeListener::class.java))
            ?.filter { it.packageName != packageName }
    }.getOrNull()

    /** Prefer whichever session is actually playing — the system's own ordering can lag behind
     *  an app switch, which was making source-change detection feel slow. */
    private fun pickController(controllers: List<MediaController>?): MediaController? =
        controllers?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: controllers?.firstOrNull()

    private fun trackController(controller: MediaController) {
        if (trackedController?.sessionToken != controller.sessionToken) {
            controllerCallback?.let { trackedController?.unregisterCallback(it) }
            trackedController = controller
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = syncSession(controller)
                override fun onMetadataChanged(metadata: android.media.MediaMetadata?) = syncSession(controller)
                override fun onSessionDestroyed() = clearSession()
            }
            controllerCallback = callback
            controller.registerCallback(callback)

            // OriginPlayer's native tap handler doesn't honor sessionActivity at all — verified via
            // logging that this resolves correctly, yet tapping still opened our own app. It just
            // opens the app that owns the session identity (us). MainActivity redirects instead —
            // see its onCreate(). This is still set for anything that DOES honor it (lock screen etc).
            val target = packageManager.getLaunchIntentForPackage(controller.packageName)
                ?: Intent(this, MainActivity::class.java)
            mediaSession?.setSessionActivity(
                PendingIntent.getActivity(
                    this, controller.packageName.hashCode(), target,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        syncSession(controller)
    }

    /** Mirror [controller]'s real metadata/playback state onto our own (spoofed-identity) session. */
    private fun syncSession(controller: MediaController) {
        val session = mediaSession ?: return
        session.setMetadata(controller.metadata)
        val state = controller.playbackState ?: PlaybackState.Builder()
            .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions(
                PlaybackState.ACTION_PLAY_PAUSE or
                    PlaybackState.ACTION_SKIP_TO_NEXT or
                    PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            )
            .build()
        session.setPlaybackState(state)
        session.isActive = true
    }

    private fun clearSession() {
        controllerCallback?.let { trackedController?.unregisterCallback(it) }
        trackedController = null
        controllerCallback = null
        mediaSession?.isActive = false
    }
}
