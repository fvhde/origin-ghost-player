package com.originghostplayer.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.session.PlaybackState
import android.view.KeyEvent

/**
 * Real player sessions (checked via dumpsys media_session on this device) all have a populated
 * mediaButtonReceiver; ours didn't. This gives MediaProbeListener's session one, wired straight
 * back to the tracked external controller.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return
        if (event.action != KeyEvent.ACTION_DOWN) return
        val controller = MediaProbeListener.instance?.currentController() ?: return
        val controls = controller.transportControls
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> controls.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> controls.pause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ->
                if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            KeyEvent.KEYCODE_MEDIA_NEXT -> controls.skipToNext()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> controls.skipToPrevious()
        }
    }
}
