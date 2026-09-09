package com.originghostplayer.android

import android.app.PendingIntent
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/**
 * Listens system-wide for other apps' media sessions ONLY, and mirrors whichever one is playing
 * onto this app's OWN MediaSession — under this app's spoofed identity — with NO notification of
 * our own posted at any point. Confirmed working: OriginOS's native OriginPlayer picks this
 * session up regardless of the real source app (tested with Metrolist and Firefox).
 *
 * Suppressed entirely while the phone is in car mode (Android Auto projects onto the phone by
 * putting it into UI_MODE_TYPE_CAR): the real source app's session already reaches the head unit
 * directly, so mirroring it too made Android Auto's media screen show the same "now playing" info
 * twice, as two separate players.
 *
 * Also suppressed during an active phone/VoIP call: some dialers expose a MediaSession (for
 * Bluetooth/Android Auto call-control surfaces) reporting STATE_PLAYING, which this probe would
 * otherwise mirror as if it were music — see isInCall().
 */
class MediaProbeListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: MediaProbeListener? = null

        private const val POLL_INTERVAL_MS = 1000L

        /** How long a source with no real PlaybackState (Firefox observed) can sit unchanged
         *  before we give up mirroring it as "playing" and clear it instead. See [syncSession]. */
        private const val UNKNOWN_STATE_STALE_MS = 3 * 60 * 1000L

        /** Floor between silent-blip bursts (each burst is 2 blips, ~2.1s apart end-to-end — see
         *  [syncSession]). Defense in depth against any source whose PlaybackState itself bounces
         *  off PLAYING and back in rapid, repeated transitions (whatever the cause) queuing bursts
         *  faster than they can drain: unbounded, that was observed pinning the system's AudioTrack
         *  pool (rapid "No More Track Available" from repeated blip creation) when a real player's
         *  onPlay() handler turned out not to be idempotent — see the removed resume-on-completion
         *  call this used to pair with, in git history. */
        private const val MIN_BLIP_BURST_INTERVAL_MS = 2500L
    }

    private var mediaSession: MediaSession? = null
    private var trackedController: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null
    private val pollHandler = Handler(Looper.getMainLooper())

    /** Registered in [onListenerConnected], torn down in [onListenerDisconnected] — see
     *  [isInCarMode]. */
    private var carModeReceiver: BroadcastReceiver? = null

    /** Was the currently-tracked controller PLAYING as of the last [syncSession] call? Drives the
     *  silent-blip re-announcement below — reset whenever the tracked controller itself changes. */
    private var lastKnownPlaying = false

    /** Bookkeeping for the no-real-PlaybackState staleness check in [syncSession]: when we started
     *  assuming "playing" for the current controller's current metadata, and what that metadata was. */
    private var unknownStateSince = 0L
    private var unknownStateMetadataKey: String? = null

    /** [SystemClock.elapsedRealtime] of the last silent-blip burst we actually fired — see
     *  [MIN_BLIP_BURST_INTERVAL_MS]. */
    private var lastBlipBurstAt = 0L

    /** Exposed for [MediaButtonReceiver], which has no other way to reach the tracked controller. */
    fun currentController(): MediaController? = trackedController

    private val pollRunnable = object : Runnable {
        override fun run() {
            maybeTrack(pickController(activeSessions()))
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            maybeTrack(pickController(controllers?.filter { it.packageName != packageName }))
        }

    /** True while Android Auto has the phone projected onto a head unit. See the class doc for
     *  why mirroring is suppressed in that state. */
    private fun isInCarMode(): Boolean =
        (getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
            ?.currentModeType == Configuration.UI_MODE_TYPE_CAR

    /** True during an active phone or VoIP call. AudioManager's mode (unlike TelecomManager's
     *  call state) needs no extra permission and reflects both cellular and VoIP calls alike. */
    private fun isInCall(): Boolean =
        (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode.let {
            it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION
        }

    private fun maybeTrack(controller: MediaController?) {
        if (isInCarMode() || isInCall()) {
            clearSession()
            return
        }
        controller?.let(::trackController) ?: clearSession()
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
        carModeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    // Entering car mode: drop our mirrored session immediately rather than
                    // waiting for the next poll tick, so the head unit never briefly sees both.
                    UiModeManager.ACTION_ENTER_CAR_MODE -> clearSession()
                    UiModeManager.ACTION_EXIT_CAR_MODE -> maybeTrack(pickController(activeSessions()))
                }
            }
        }.also {
            registerReceiver(
                it,
                IntentFilter().apply {
                    addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
                    addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
                },
                Context.RECEIVER_NOT_EXPORTED,
            )
        }
        maybeTrack(pickController(activeSessions()))
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
        carModeReceiver?.let { runCatching { unregisterReceiver(it) } }
        carModeReceiver = null
        clearSession()
        mediaSession?.release()
        mediaSession = null
        // A system-initiated unbind DOES snooze the component, so unlike the process-kill case a
        // plain requestRebind works here — self-heals transient disconnects without user action.
        runCatching { requestRebind(ComponentName(this, MediaProbeListener::class.java)) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        if (isInCarMode() || isInCall()) return
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

            // A genuinely new controller starts "unknown" rather than assumed-playing — syncSession
            // below compares against this and fires the re-announcement blip itself the moment the
            // real state comes in as PLAYING, same as a resume on an existing controller would.
            lastKnownPlaying = false
            // A different source shouldn't be held back by the outgoing one's debounce window.
            lastBlipBurstAt = 0L
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
                },
                durationMs + 100L,
            )
        }
    }

    /** Mirror [controller]'s real metadata/playback state onto our own (spoofed-identity) session. */
    private fun syncSession(controller: MediaController) {
        val session = mediaSession ?: return

        // Some sources (Firefox observed) never report a PlaybackState at all — there's no real
        // state to mirror, ever, and no transition to notice when they actually stop. Defaulting
        // that case to STATE_PLAYING is what makes OriginPlayer notice such a source at all (the
        // blip below only fires on a PLAYING transition) — defaulting to PAUSED instead silences
        // it completely, which regressed Firefox not showing up at all. So: keep assuming PLAYING,
        // but only as long as the metadata (title/artist/album) is actually still changing — once
        // it's sat unchanged past the timeout, treat it as finished/abandoned and clear instead of
        // reporting it as playing forever.
        if (controller.playbackState == null) {
            val md = controller.metadata
            val key = listOf(
                md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE),
                md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST),
                md?.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM),
            ).joinToString("|")
            val now = SystemClock.elapsedRealtime()
            if (key != unknownStateMetadataKey) {
                unknownStateMetadataKey = key
                unknownStateSince = now
            } else if (now - unknownStateSince > UNKNOWN_STATE_STALE_MS) {
                clearSession()
                return
            }
        } else {
            unknownStateMetadataKey = null
            unknownStateSince = 0L
        }

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

        // Real players register in AudioManager's AudioPlaybackConfiguration list (actually
        // outputting audio) when they start; our passive session-mirror never does. Play a
        // genuinely silent clip — deliberately WITHOUT ever requesting audio focus, so there's no
        // focus-arbitration event to pause/duck the real app — purely to register as active audio
        // output, which is what OriginPlayer's takeover turned out to watch for.
        //
        // Fired on every transition INTO playing, not just when a brand-new controller shows up:
        // OriginPlayer can apparently let go of us during a paused/idle stretch (matching the
        // original "cold start needs a manual reselect" behavior this blip was built to fix), and
        // resuming an EXISTING controller used to never re-trigger it, leaving OriginPlayer stuck
        // showing us as selected but not updating until you manually reselected another app and
        // back. Re-announcing on every playing-transition covers both cases uniformly.
        val isPlayingNow = state.state == PlaybackState.STATE_PLAYING
        val now = SystemClock.elapsedRealtime()
        if (isPlayingNow && !lastKnownPlaying && now - lastBlipBurstAt >= MIN_BLIP_BURST_INTERVAL_MS) {
            lastBlipBurstAt = now
            playSilentBlip(controller, durationMs = 800)
            pollHandler.postDelayed({ playSilentBlip(controller, durationMs = 800) }, 1200L)
        }
        lastKnownPlaying = isPlayingNow
    }

    private fun clearSession() {
        controllerCallback?.let { trackedController?.unregisterCallback(it) }
        trackedController = null
        controllerCallback = null
        lastKnownPlaying = false
        unknownStateMetadataKey = null
        unknownStateSince = 0L
        // isActive=false alone left OriginPlayer's chip stuck animating "playing" indefinitely —
        // the last PlaybackState it ever saw from us was STATE_PLAYING, and nothing had told it
        // otherwise. Push an explicit STOPPED state (and drop the stale metadata) before going
        // inactive, so there's a real "this stopped" signal for it to react to.
        mediaSession?.setPlaybackState(
            PlaybackState.Builder()
                .setState(PlaybackState.STATE_STOPPED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        mediaSession?.setMetadata(null)
        mediaSession?.isActive = false
    }
}
