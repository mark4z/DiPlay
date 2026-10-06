package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.hud.BydHardwareIntegration
import java.util.concurrent.ExecutorService
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VirtualClusterDisplayTest {
    private lateinit var activity: CarPlayHostActivity

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        activity.getSharedPreferences("diplay_cluster_layout", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After fun tearDown() {
        MapMirrors.streamAspect = MapMirrors.PHYSICAL_STREAM_ASPECT
        for (name in listOf("mainHandler", "teardownExecutor", "airPlayCommandExecutor")) {
            val value = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
            if (value is Handler) value.removeCallbacksAndMessages(null)
            if (value is ExecutorService) value.shutdownNow()
        }
    }

    @Test fun virtualStreamRemainsOptIn() { assertNull(config()) }

    @Test fun nativeVirtualStreamNegotiates16By9AndSetsMirrorAspect() {
        AirPlayPersistence.saveClusterMapEnabled(activity, true)
        AirPlayPersistence.saveClusterMapScalePercent(activity, 100)
        MapMirrors.streamAspect = MapMirrors.PHYSICAL_STREAM_ASPECT
        val display = config()!!
        assertEquals(1280, display.widthPixels)
        assertEquals(720, display.heightPixels)
        assertEquals(16.0 / 9, MapMirrors.streamAspect, 0.00001)
        assertEquals(display.safeArea!!.left, display.safeArea!!.right)
        assertFalse(field("clusterStreamOnDisplay"))
        assertFalse(field("adbClusterConfigured"))
    }

    @Test fun virtualStreamHonorsSavedScaleMarkerAndContentSettings() {
        AirPlayPersistence.saveClusterMapEnabled(activity, true)
        AirPlayPersistence.saveClusterMarkerHorizontalStep(activity, 2)
        AirPlayPersistence.saveClusterMarkerVerticalStep(activity, -1)
        for (content in CarPlayClusterDisplay.Content.entries) {
            AirPlayPersistence.saveClusterContent(activity, content)
            for (scale in listOf(83, 67, 100)) {
                AirPlayPersistence.saveClusterMapScalePercent(activity, scale)
                val expected = CarPlayClusterDisplay.config(1280, 720, scale, 2, -1, content,
                    CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT)
                val actual = config()!!
                assertEquals(expected, actual)
                assertNotEquals(actual.safeArea!!.left, actual.safeArea!!.right)
                assertNotEquals(actual.safeArea!!.top, actual.safeArea!!.bottom)
                assertEquals(scale, AirPlayPersistence.loadClusterMapScalePercent(activity))
            }
        }
    }

    @Test fun freshVirtualStreamRetainsItsOriginalNativeSize() {
        AirPlayPersistence.saveClusterMapEnabled(activity, true)
        val display = config()!!
        assertEquals(100, AirPlayPersistence.loadVirtualMapScalePercent(activity))
        assertEquals(1280, display.widthPixels)
        assertEquals(720, display.heightPixels)
    }

    @Test fun savedBydOptionsAndRecognizedDisplaysCannotReactivatePhysicalRouting() {
        assertFalse(BydHardwareIntegration.ENABLED)
        AirPlayPersistence.saveAdbClusterEnabled(activity, true)
        DiLink51ClusterLayout.saveAutomatic(activity, true)
        AirPlayPersistence.saveClusterMapScalePercent(activity, 67)
        AirPlayPersistence.saveClusterMarkerHorizontalStep(activity, 1)
        AirPlayPersistence.saveClusterMarkerVerticalStep(activity, -1)
        val manager = activity.getSystemService(DisplayManager::class.java)
        val ids = listOf(DiLink4ClusterDisplay.NAME, DiLink51ClusterLayout.BASE,
            DiLink51ClusterLayout.FULL, DiLink51ClusterLayout.SIDE).map { name ->
            ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5).also { id ->
                shadowOf(manager.getDisplay(id)).apply { setName(name); setFlags(Display.FLAG_PRESENTATION) }
            }
        }
        val original = android.os.Build.FINGERPRINT
        try {
            for (fingerprint in listOf("unknown", "BYD-AUTO/DiLink5.0/DiLink5.0:12/test",
                DiLink51ClusterLayout.FINGERPRINT)) {
                ShadowBuild.setFingerprint(fingerprint)
                assertFalse(AdbClusterRouter.enabled(activity))
                assertFalse(DiLink51ClusterLayout.automatic(activity))
                for (theme in DiLink51ClusterLayout.Theme.entries) {
                    DiLink51ClusterLayout.saveTheme(activity, theme)
                    assertNull(ClusterMapPresentation.findDisplay(activity, theme))
                    assertEquals(CarPlayClusterDisplay.config(1280, 720, 67, 1, -1,
                        baseSafeArea = CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT), config())
                    assertFalse(field("clusterStreamOnDisplay"))
                    assertFalse(field("adbClusterConfigured"))
                }
            }
            // Saved hardware options must not override the generic map's own opt-in switch.
            AirPlayPersistence.saveClusterMapEnabled(activity, false)
            assertNull(config())
            assertTrue(activity.getSharedPreferences("diplay_cluster_layout", 0).getBoolean("automatic", false))
        } finally {
            ShadowBuild.setFingerprint(original)
            ids.forEach(ShadowDisplayManager::removeDisplay)
        }
    }

    private fun field(name: String): Boolean = activity.javaClass
        .getDeclaredField(name).apply { isAccessible = true }.getBoolean(activity)

    private fun config(): AirPlayDisplayConfig? = activity.javaClass
        .getDeclaredMethod("clusterDisplayConfig").apply { isAccessible = true }.invoke(activity) as AirPlayDisplayConfig?
}
