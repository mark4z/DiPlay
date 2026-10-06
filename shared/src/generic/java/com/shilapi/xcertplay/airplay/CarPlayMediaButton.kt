package com.shilapi.xcertplay.airplay

import android.view.KeyEvent

/** Android-standard key events mapped to the CarPlay media HID report. */
object CarPlayMediaButton {
    const val PLAY = 1
    const val PAUSE = 2
    const val PLAY_PAUSE = 3
    const val NEXT = 4
    const val PREVIOUS = 5

    fun opensSiri(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_VOICE_ASSIST

    fun forKeyCode(keyCode: Int): Int? = when (keyCode) {
        KeyEvent.KEYCODE_MEDIA_PLAY -> PLAY
        KeyEvent.KEYCODE_MEDIA_PAUSE -> PAUSE
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> PLAY_PAUSE
        KeyEvent.KEYCODE_MEDIA_NEXT -> NEXT
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PREVIOUS
        else -> null
    }
}
