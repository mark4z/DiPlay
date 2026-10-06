package com.shilapi.xcertplay

import android.app.Activity
import android.content.Context
import com.shilapi.xcertplay.airplay.SafeAreaRect
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [ClusterCalibrationRouteTest.Router::class])
internal class ClusterCalibrationRouteTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val previewHosts = mutableListOf<Activity>()
    private val rect = SafeAreaRect(300, 100, 1500, 620)

    @Before fun reset() {
        ClusterActivityOutput.stopForSettings()
        AirPlayPersistence.saveAdbClusterEnabled(app, true)
        Router.launches = 0
    }

    @After fun cleanup() {
        ClusterActivityOutput.stopForSettings()
        AirPlayPersistence.saveAdbClusterEnabled(app, false)
        AirPlayPersistence.saveClusterMapEnabled(app, false)
        previewHosts.clear()
    }

    private fun preview(owner: Any) {
        val host = Robolectric.buildActivity(Activity::class.java).get().also(previewHosts::add)
        ClusterActivityOutput.beginSafeAreaPreview(owner, rect, host)
        assertEquals(rect, ClusterActivityOutput.previewRect)
        assertFalse(ClusterActivityOutput.launchPending)
        assertFalse(ClusterActivityOutput.hasConfirmedRoute())
        assertEquals(0, Router.launches)
    }

    @Test fun editorKeepsLocalPreviewWithoutLaunchingHardwareBeforeCarPlay() {
        val owner = Any()
        preview(owner)
        assertEquals(-1, ClusterActivityOutput.mainTaskId)
        assertFalse(ClusterActivityOutput.streamActive)
        assertFalse(ClusterActivityOutput.acceptsToken("01234567-89ab-cdef-0123-456789abcdef"))
        ClusterActivityOutput.endSafeAreaPreview(owner)
        assertNull(ClusterActivityOutput.previewRect)
        assertFalse(ClusterActivityOutput.launchPending)
    }

    @Test fun previewCannotAdmitAClusterWindowWithSavedHardwareSettings() {
        val owner = Any()
        preview(owner)
        val window = Robolectric.buildActivity(AdbClusterActivity::class.java).get()
        assertFalse(ClusterActivityOutput.confirm(window, "01234567-89ab-cdef-0123-456789abcdef", 7))
        ClusterActivityOutput.endSafeAreaPreview(owner)
        assertNull(ClusterActivityOutput.previewRect)
        assertFalse(ClusterActivityOutput.hasConfirmedRoute())
    }

    @Test fun dismissingTheEditorPreservesLocalPlaybackStateWithoutRouting() {
        val editor = Any()
        preview(editor)
        val playback = Any()
        ClusterActivityOutput.bind(playback, 42) { }
        ClusterActivityOutput.setStreamActive(true)
        ClusterActivityOutput.endSafeAreaPreview(editor)
        assertFalse(ClusterActivityOutput.hasConfirmedRoute())
        assertTrue(ClusterActivityOutput.streamActive)
        assertEquals(42, ClusterActivityOutput.mainTaskId)
        assertNull(ClusterActivityOutput.previewRect)
        assertEquals(0, Router.launches)
        ClusterActivityOutput.stop(playback)
        assertFalse(ClusterActivityOutput.streamActive)
    }

    @Test fun staleEditorCannotClearReplacementLocalPreview() {
        val first = Any()
        preview(first)
        val second = Any()
        preview(second)
        ClusterActivityOutput.endSafeAreaPreview(first)
        assertEquals(rect, ClusterActivityOutput.previewRect)
        ClusterActivityOutput.endSafeAreaPreview(second)
        assertNull(ClusterActivityOutput.previewRect)
        assertEquals(0, Router.launches)
    }

    @Implements(AdbClusterRouter::class, isInAndroidSdk = false)
    class Router {
        @Implementation fun launch(context: Context, token: String, holdStockMap: Boolean,
            prepare: (Int) -> Boolean): AdbClusterRouter.Result {
            ++launches
            return AdbClusterRouter.Result(false, "Unexpected hardware launch")
        }
        companion object { @Volatile var launches = 0 }
    }
}
