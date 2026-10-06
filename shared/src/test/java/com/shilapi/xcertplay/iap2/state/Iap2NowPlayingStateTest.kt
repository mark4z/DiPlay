package com.shilapi.xcertplay.iap2.state

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2NowPlayingStateTest {
    @Test
    fun keepsFullLengthTitleAndArtistWithoutVehicleDisplayLimits() {
        val title = "🎵 Long title ".repeat(30)
        val artist = "Artist ".repeat(40)
        val state = Iap2NowPlayingState()
        val song = state.accept(update {
            group(0) { string(1, title); string(12, artist) }
            group(1) { u8(0, 1) }
        })!!
        assertEquals(title.trim(), song.title)
        assertEquals(artist.trim(), song.artist)
        assertEquals("${title.trim()} — ${artist.trim()}", song.text)
        assertTrue(song.text.toByteArray(Charsets.UTF_16LE).size > 255)
        assertTrue(song.playing)
    }

    @Test
    fun partialUpdatesRetainMetadataAndSeekStatesArePlaying() {
        val state = Iap2NowPlayingState()
        state.accept(update { group(0) { string(1, "Track"); string(12, "Artist") } })
        for (status in listOf(1, 3, 4)) {
            state.accept(update { group(1) { u8(0, status) } })
            assertEquals(Iap2NowPlaying("Track", "Artist", true), state.current())
        }
        assertNull(state.accept(update { group(1) { u32(1, 120_000) } }))
        assertEquals(Iap2NowPlaying("Next", "Artist", true),
            state.accept(update { group(0) { string(1, "Next") } }))
        state.accept(update { group(1) { u8(0, 2) } })
        assertFalse(state.current()!!.playing)
    }

    @Test
    fun emptyTitleClearsTheItemAndItsArtistButRetainsPlaybackState() {
        val state = Iap2NowPlayingState()
        state.accept(update {
            group(0) { string(1, "Old"); string(12, "Artist") }
            group(1) { u8(0, 1) }
        })
        assertNull(state.accept(update { group(0) { string(1, "  "); string(12, "Stale artist") } }))
        assertNull(state.current())
        assertEquals(Iap2NowPlaying("New", null, true),
            state.accept(update { group(0) { string(1, "New") } }))
    }

    @Test
    fun emptyArtistClearsOnlyArtistAndClearEndsTheSession() {
        val state = Iap2NowPlayingState()
        state.accept(update { group(0) { string(1, "Track"); string(12, "Artist") } })
        assertEquals(Iap2NowPlaying("Track", null, false),
            state.accept(update { group(0) { string(12, "") } }))
        state.clear()
        assertNull(state.current())
        assertNull(state.accept(update { group(1) { u8(0, 1) } }))
        assertNull(state.current())
    }

    @Test
    fun unrelatedAndMalformedFramesDoNotChangeTheSong() {
        val state = Iap2NowPlayingState()
        val frame = update { group(0) { string(1, "Track"); string(12, "Artist") } }
        state.accept(frame)
        val before = state.current()
        assertNull(state.accept(Iap2Messages.buildRaw(0x5201) { u8(1, 0) }))
        assertEquals(before, state.current())
        assertNull(state.accept(update { bytes(0, byteArrayOf(0, 8, 0, 1, 1)) }))
        assertEquals(before, state.current())
    }

    @Test
    fun changesBeyondTypicalDisplayWidthStillProduceAnUpdate() {
        val state = Iap2NowPlayingState()
        val title = "T".repeat(200)
        state.accept(update { group(0) { string(1, title); string(12, "First artist") } })
        val next = state.accept(update { group(0) { string(12, "Second artist") } })!!
        assertEquals("$title — Second artist", next.text)
    }

    private fun update(block: Iap2BodyBuilder.() -> Unit) =
        Iap2Messages.buildRaw(Iap2NowPlayingState.NOW_PLAYING_UPDATE, block)
}
