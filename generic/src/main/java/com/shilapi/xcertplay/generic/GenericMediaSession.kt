package com.shilapi.xcertplay.generic

import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.io.Closeable

/** Standard Android media-session integration; audio routing/focus belongs to AndroidMediaSink. */
internal class GenericMediaSession(context: Context, private val carPlayController: CarPlayController) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var playing = false
    private val session = MediaSession(context, "DiPlay Generic").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() { carPlayController.sendMediaButton(CarPlayMediaButton.PLAY) }
            override fun onPause() { carPlayController.sendMediaButton(CarPlayMediaButton.PAUSE) }
            override fun onSkipToNext() { carPlayController.sendMediaButton(CarPlayMediaButton.NEXT) }
            override fun onSkipToPrevious() { carPlayController.sendMediaButton(CarPlayMediaButton.PREVIOUS) }
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                @Suppress("DEPRECATION")
                val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
                val index = CarPlayMediaButton.forKeyCode(event.keyCode) ?: return false
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) carPlayController.sendMediaButton(index)
                return true
            }
        }, main)
    }

    init {
        carPlayController.playbackListener = { active -> onPlaying(active) }
        carPlayController.nowPlayingListener = { info ->
            main.post {
                if (!closed) {
                    session.setMetadata(MediaMetadata.Builder().apply {
                        info.title?.let { putString(MediaMetadata.METADATA_KEY_TITLE, it) }
                        info.artist?.let { putString(MediaMetadata.METADATA_KEY_ARTIST, it) }
                        info.album?.let { putString(MediaMetadata.METADATA_KEY_ALBUM, it) }
                        info.durationMillis?.let { putLong(MediaMetadata.METADATA_KEY_DURATION, it) }
                    }.build())
                    publish(info.playing, info.elapsedMillis ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN)
                }
            }
        }
    }

    fun connected() {
        if (closed) return
        session.isActive = true
        publish(playing)
    }

    fun onPlaying(active: Boolean) {
        main.post { if (!closed) publish(active) }
    }

    private fun publish(active: Boolean, position: Long = PlaybackState.PLAYBACK_POSITION_UNKNOWN) {
        playing = active
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (active) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, position, if (active) 1f else 0f)
            .build())
    }

    override fun close() {
        if (closed) return
        closed = true
        carPlayController.playbackListener = null
        carPlayController.nowPlayingListener = null
        session.isActive = false
        session.release()
        main.removeCallbacksAndMessages(null)
    }
}
