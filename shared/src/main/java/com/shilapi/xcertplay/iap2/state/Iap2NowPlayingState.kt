package com.shilapi.xcertplay.iap2.state

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** Now-playing metadata at full length, independent of any vehicle display's text limits. */
internal data class Iap2NowPlaying(val title: String, val artist: String?, val playing: Boolean) {
    val text: String get() = artist?.let { "$title — $it" } ?: title
}

/**
 * Incremental iAP2 NowPlayingUpdate (0x5001) state. Omitted fields retain their previous values;
 * an explicitly cleared title forgets the item. Display-specific formatting belongs to consumers.
 */
internal class Iap2NowPlayingState {
    private var title: String? = null
    private var artist: String? = null
    private var playing = false
    private var last: Iap2NowPlaying? = null

    /** Updates the cached item; null can mean unchanged or cleared, so consumers compare [current]. */
    fun accept(frame: Iap2Frame): Iap2NowPlaying? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        runCatching { body.optionalGroup(ITEM) }.getOrNull()?.let { item ->
            val nextTitle = runCatching { item.optionalString(TITLE) }.getOrNull()
            if (nextTitle != null) {
                title = nextTitle
                if (nextTitle.isBlank()) artist = null
            }
            if (nextTitle?.isBlank() != true) {
                runCatching { item.optionalString(ARTIST) }.getOrNull()?.let { artist = it }
            }
        }
        runCatching { body.optionalGroup(PLAYBACK)?.optionalU8(STATUS) }.getOrNull()?.let { status ->
            playing = status == STATUS_PLAYING || status == STATUS_SEEK_FORWARD || status == STATUS_SEEK_BACKWARD
        }
        val next = title?.trim()?.takeIf { it.isNotEmpty() }?.let {
            Iap2NowPlaying(it, artist?.trim()?.takeIf { name -> name.isNotEmpty() }, playing)
        }
        if (next == last) return null
        last = next
        return next
    }

    /** The song metadata known so far, if any. */
    fun current(): Iap2NowPlaying? = last

    /** The session ended: forget the song. */
    fun clear() {
        title = null
        artist = null
        playing = false
        last = null
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val ITEM = 0
        private const val TITLE = 1
        private const val ARTIST = 12
        private const val PLAYBACK = 1
        private const val STATUS = 0
        private const val STATUS_PLAYING = 1
        private const val STATUS_SEEK_FORWARD = 3
        private const val STATUS_SEEK_BACKWARD = 4
    }
}
