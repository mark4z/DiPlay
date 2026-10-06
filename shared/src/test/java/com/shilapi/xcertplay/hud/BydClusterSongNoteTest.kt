package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [29], shadows = [BydClusterSongNoteTest.Shell::class])
class BydClusterSongNoteTest {
    private val type = BydClusterSong::class.java
    private val app get() = RuntimeEnvironment.getApplication()
    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private fun get(name: String) = field(name).get(BydClusterSong)
    private fun set(name: String, value: Any?) = field(name).set(BydClusterSong, value)
    private fun drain() { (get("writer") as ScheduledExecutorService).submit {}.get(5, TimeUnit.SECONDS) }

    private fun song(title: String) = BydClusterSong.onFrame(
        Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) {
            group(0) { string(1, title); string(12, "Artist") }
        },
    )

    @Before fun setup() {
        drain()
        (get("state") as ClusterSongState).clear()
        listOf("note", "onChange", "announced", "wanted", "shown").forEach { set(it, null) }
        BydClusterSong.attach(app)
        BydOutputSettings.setClusterSong(app, true)
        BydOutputSettings.setClusterSongOnChange(app, true)
        Shell.commands.clear()
    }

    @After fun cleanup() {
        BydOutputSettings.setClusterSong(app, false)
        BydOutputSettings.setClusterSongOnChange(app, false)
        BydClusterSong.end()
        drain()
        set("context", null)
    }

    @Test fun wheelNotesCannotStartShellWritesOrNoteTimersWithSavedSongPreferences() {
        song("Track")
        val current = BydClusterSong.current()
        BydClusterSong.note("Zoom", source = 7)
        BydClusterSong.note("Joystick on")
        assertEquals(current, BydClusterSong.current())
        assertHardwareIdle()
    }

    @Test fun wheelNotesCannotBypassHardwareDisconnectionWhenSongOutputIsOff() {
        BydOutputSettings.setClusterSong(app, false)
        BydClusterSong.settingChanged(false)
        BydClusterSong.note("Volume", source = 7)
        assertNull(BydClusterSong.current())
        assertHardwareIdle()
        song("Track")
        BydClusterSong.note("Zoom")
        assertEquals(ClusterSong("Track — Artist", false, "Track"), BydClusterSong.current())
        assertHardwareIdle()
    }

    @Test fun ignoredNotesDoNotInterfereWithLaterMetadataAndPlaybackUpdates() {
        song("First")
        BydClusterSong.note("Zoom", source = 7)
        song("Second")
        BydClusterSong.onFrame(Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) {
            group(1) { u8(0, 1) }
        })
        assertEquals(ClusterSong("Second — Artist", true, "Second"), BydClusterSong.current())
        assertHardwareIdle()
    }

    @Test fun notesBeforeAndAfterDisconnectCannotResurrectAnOldSongOrHardwareCard() {
        song("Old")
        BydClusterSong.note("Old note")
        BydClusterSong.end()
        assertNull(BydClusterSong.current())
        BydClusterSong.note("Between sessions")
        assertNull(BydClusterSong.current())
        assertHardwareIdle()
        song("New")
        BydClusterSong.note("New note")
        assertEquals(ClusterSong("New — Artist", false, "New"), BydClusterSong.current())
        assertHardwareIdle()
    }

    private fun assertHardwareIdle() {
        drain()
        assertFalse(BydHardwareIntegration.ENABLED)
        assertTrue("No BYD shell command may run", Shell.commands.isEmpty())
        assertNull(get("note"))
        assertNull(get("onChange"))
        assertNull(get("announced"))
        assertNull(get("wanted"))
        assertNull(get("shown"))
    }

    @Implements(BydAdbShell::class, isInAndroidSdk = false)
    class Shell {
        @Implementation fun run(context: Context, command: String): String? {
            commands.add(command)
            return null
        }
        companion object {
            val commands = CopyOnWriteArrayList<String>()
        }
    }
}
