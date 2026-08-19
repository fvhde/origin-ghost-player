package com.originghostplayer.android

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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
        val controller = runCatching { MediaController(this, token) }.getOrNull() ?: return
        // This path bypassed pickController's sticky logic entirely: a paused app re-posting its
        // notification (which pausing typically does) would yank tracking straight back onto it
        // mid-transition, on top of whatever the poll/sessionsChangedListener had already settled
        // on — an extra flicker step. Only take over here if it's actually playing.
        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            trackController(controller)
        }
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

    /** Sticky: keep whatever we're already tracking as long as it's still playing, rather than
     *  re-picking "first PLAYING" from the list every poll tick. During a handoff between two
     *  apps, both can briefly report PLAYING simultaneously — re-evaluating from scratch each
     *  tick made the island flicker between the old and new album art until one settled. Only
     *  look for a different session once the tracked one actually stops playing (or disappears). */
    private fun pickController(controllers: List<MediaController>?): MediaController? {
        val list = controllers ?: return null
        trackedController?.let { current ->
            val stillPlaying = list.firstOrNull { it.sessionToken == current.sessionToken }
                ?.takeIf { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            if (stillPlaying != null) return stillPlaying
        }
        return list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list.firstOrNull()
    }

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

            // Real players register in AudioManager's AudioPlaybackConfiguration list (actually
            // outputting audio) when they start; our passive session-mirror never does. Play a
            // genuinely silent clip — deliberately WITHOUT ever requesting audio focus, so there's
            // no focus-arbitration event to pause/duck the real app — purely to register as active
            // audio output, which is what OriginPlayer's takeover turned out to watch for. Fired
            // twice (800ms each) for reliability against a real app's own still-active output.
            //
            // Only when actually playing: this used to fire (and unconditionally resume playback
            // afterwards) for a PAUSED controller too — e.g. whenever the listener reconnects after
            // being idle and re-picks-up whatever session is there — which meant a track you'd
            // deliberately paused could resume on its own later. Gate on the real state, both here
            // and again right before the resume call below (it can change mid-blip).
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                playSilentBlip(controller, durationMs = 800)
                pollHandler.postDelayed({ playSilentBlip(controller, durationMs = 800) }, 1200L)
            }
        }
        syncSession(controller)
    }

    private fun playSilentBlip(controller: MediaController, durationMs: Int) {
        runCatching {
            val sampleRate = 44100
            val silence = ShortArray(sampleRate * durationMs / 1000)
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            )
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuf, silence.size * 2))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.setVolume(0f)
            track.write(silence, 0, silence.size)
            track.play()
            pollHandler.postDelayed(
                {
                    runCatching { track.stop() }
                    runCatching { track.release() }
                    // Only resume if it's still actually playing — never resume something the
                    // user (or anything else) paused while our blip was running.
                    if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                        runCatching { controller.transportControls.play() }
                    }
                },
                durationMs + 100L,
            )
        }
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
