package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BydClusterSongAdapterTest {
    @Test
    fun adapterKeepsDashboardLimitAndFullHudTitle() {
        val title = "a" + "🎵".repeat(100)
        val state = ClusterSongState()
        val song = state.accept(Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) {
            group(0) { string(1, title); string(12, "Artist") }
        })!!
        assertEquals(ClusterSongState.text(title, "Artist"), song.text)
        assertEquals(title, song.line)
        assertTrue(song.text.toByteArray(Charsets.UTF_16LE).size <= ClusterSongState.MAX_TEXT_BYTES)
        assertTrue(!Character.isHighSurrogate(song.text.last()))
    }

    @Test
    fun hiddenArtistChangesDoNotRepublishAnIdenticalDashboardCard() {
        val title = "T".repeat(200)
        val state = ClusterSongState()
        state.accept(Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) {
            group(0) { string(1, title); string(12, "First artist") }
        })
        val before = state.current()
        assertNull(state.accept(Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) {
            group(0) { string(12, "Second artist") }
        }))
        assertEquals(before, state.current())
        val next = state.accept(Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) {
            group(0) { string(1, "Short title") }
        })!!
        assertEquals("Short title — Second artist", next.text)
    }
}
