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
@Config(manifest = Config.NONE, sdk = [29], shadows = [BydClusterSongTimerTest.Shell::class])
class BydClusterSongTimerTest {
    private val type = BydClusterSong::class.java
    private val app get() = RuntimeEnvironment.getApplication()
    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private fun get(name: String) = field(name).get(BydClusterSong)
    private fun set(name: String, value: Any?) = field(name).set(BydClusterSong, value)
    private fun drain() { (get("writer") as ScheduledExecutorService).submit {}.get(5, TimeUnit.SECONDS) }

    private fun song(title: String = "Track") = BydClusterSong.onFrame(
        Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) {
            group(0) { string(1, title); string(12, "Artist") }
        },
    )

    private fun playback(playing: Boolean) = BydClusterSong.onFrame(
        Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) {
            group(1) { u8(0, if (playing) 1 else 2) }
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

    @Test fun savedSongPreferencesCannotScheduleAnOutputWindow() {
        val settings = app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE)
        assertTrue(settings.getBoolean("cluster_song", false))
        assertTrue(settings.getBoolean("cluster_song_on_change", false))
        song()
        assertEquals(ClusterSong("Track — Artist", false, "Track"), BydClusterSong.current())
        assertHardwareIdle()
    }

    @Test fun duplicateMetadataPlaybackAndTrackChangesStillUpdateGenericSongState() {
        song("First")
        song("First")
        playback(true)
        assertEquals(ClusterSong("First — Artist", true, "First"), BydClusterSong.current())
        assertHardwareIdle()
        song("Second")
        playback(false)
        assertEquals(ClusterSong("Second — Artist", false, "Second"), BydClusterSong.current())
        assertHardwareIdle()
    }

    @Test fun togglingSongAndWindowSettingsCannotStartTimersOrClearMetadata() {
        song()
        val current = BydClusterSong.current()
        for (onlyOnChange in listOf(false, true)) {
            BydOutputSettings.setClusterSongOnChange(app, onlyOnChange)
            BydClusterSong.onChangeSettingChanged()
            for (enabled in listOf(false, true)) {
                BydOutputSettings.setClusterSong(app, enabled)
                BydClusterSong.settingChanged(enabled)
                assertEquals(current, BydClusterSong.current())
                assertHardwareIdle()
            }
        }
    }

    @Test fun clearedMetadataAndSessionEndNeverScheduleDashboardCleanup() {
        song("Old")
        playback(true)
        BydClusterSong.onFrame(Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) {
            group(0) { string(1, "") }
        })
        assertNull(BydClusterSong.current())
        assertHardwareIdle()
        song("Before disconnect")
        BydClusterSong.end()
        assertNull(BydClusterSong.current())
        assertHardwareIdle()
        song("New")
        assertEquals(ClusterSong("New — Artist", false, "New"), BydClusterSong.current())
        assertHardwareIdle()
    }

    @Test fun staleExpiryCallbacksCannotRecreateOutputAfterANewSession() {
        song("Old")
        BydClusterSong.end()
        song("New")
        val current = BydClusterSong.current()
        for (method in listOf("endChange", "endNote")) {
            type.getDeclaredMethod(method, Context::class.java, Any::class.java)
                .apply { isAccessible = true }.invoke(BydClusterSong, app, Any())
        }
        assertEquals(current, BydClusterSong.current())
        assertHardwareIdle()
    }

    private fun assertHardwareIdle() {
        drain()
        assertFalse(BydHardwareIntegration.ENABLED)
        assertTrue("No BYD shell command may run", Shell.commands.isEmpty())
        assertNull(get("onChange"))
        assertNull(get("note"))
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
