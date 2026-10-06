package com.shilapi.xcertplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.view.Display
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.shadows.ShadowMediaCodecList
import org.robolectric.shadows.MediaCodecInfoBuilder
import java.util.concurrent.ExecutorService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class ClusterMapScaleCapabilityTest {
    private lateinit var activity: CarPlayHostActivity
    private var displayId = -1

    @Before fun setUp() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.javaClass.getDeclaredField("hevcEnabled").apply { isAccessible = true }.set(activity, false)
        ShadowMediaCodecList.reset()
        displayId = ShadowDisplayManager.addDisplay("w1920dp-h720dp-mdpi", 5)
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(displayId)
        shadowOf(display).apply { setName(DiLink51ClusterLayout.BASE); setFlags(Display.FLAG_PRESENTATION) }
        AirPlayPersistence.saveClusterMapEnabled(activity, true)
    }

    @After fun tearDown() {
        for (name in listOf("mainHandler", "teardownExecutor", "airPlayCommandExecutor")) {
            val value = activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)
            if (value is Handler) value.removeCallbacksAndMessages(null)
            if (value is ExecutorService) value.shutdownNow()
        }
        ShadowDisplayManager.removeDisplay(displayId)
        ShadowMediaCodecList.reset()
        MapMirrors.streamAspect = MapMirrors.PHYSICAL_STREAM_ASPECT
    }

    @Test fun unsupportedLargerVirtualMapUsesSupportedNativeSizeBeforeAdvertisingIt() {
        decoder(1280, 720)
        assertSize(100, 1280, 720, cluster(125))
    }

    @Test fun capableHardwareRetainsTheSelectedSmallerMapAndItsSavedPreset() {
        decoder(3840, 2160)
        assertSize(125, 1608, 904, cluster(125))
    }

    @Test fun incompatibleAlignmentFallsBackToTheNativeAlignedCanvas() {
        decoder(3840, 2160, alignment = 16)
        assertSize(100, 1280, 720, cluster(125))
    }

    @Test fun missingCapabilitiesAndSoftwareOnlyKeepTheExistingDefault() {
        assertSize(83, 1064, 600, cluster(125))
        decoder(3840, 2160, hardware = false)
        assertSize(83, 1064, 600, cluster(125))
    }

    @Test fun existingNativePresetRetainsItsStartupPathWithoutMetadata() {
        assertSize(100, 1280, 720, cluster(100))
    }

    @Test fun decoderFallbackKeepsSavedMarkerOffsetsAndContent() {
        decoder(1280, 720)
        AirPlayPersistence.saveClusterMarkerHorizontalStep(activity, 2)
        AirPlayPersistence.saveClusterMarkerVerticalStep(activity, -1)
        AirPlayPersistence.saveClusterContent(activity, CarPlayClusterDisplay.Content.INSTRUMENTS)
        val config = cluster(125)
        assertEquals(CarPlayClusterDisplay.config(1280, 720, 100, 2, -1,
            CarPlayClusterDisplay.Content.INSTRUMENTS, CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT), config)
        assertEquals(100, AirPlayPersistence.loadClusterMapScalePercent(activity))
        assertEquals(2, AirPlayPersistence.loadClusterMarkerHorizontalStep(activity))
        assertEquals(-1, AirPlayPersistence.loadClusterMarkerVerticalStep(activity))
    }

    @Test fun theNewPresetRoundTripsThroughTheSettingsPreferences() {
        AirPlayPersistence.saveClusterMapScalePercent(activity, 125)
        assertEquals(125, AirPlayPersistence.loadClusterMapScalePercent(activity))
        AirPlayPersistence.saveClusterMapScalePercent(activity, 150)
        assertEquals(125, AirPlayPersistence.loadClusterMapScalePercent(activity))
    }

    private fun cluster(scale: Int): AirPlayDisplayConfig {
        AirPlayPersistence.saveClusterMapScalePercent(activity, scale)
        return activity.javaClass.getDeclaredMethod("clusterDisplayConfig")
            .apply { isAccessible = true }.invoke(activity) as AirPlayDisplayConfig
    }

    private fun assertSize(scale: Int, width: Int, height: Int, config: AirPlayDisplayConfig) {
        assertEquals(width, config.widthPixels)
        assertEquals(height, config.heightPixels)
        assertEquals(scale, AirPlayPersistence.loadClusterMapScalePercent(activity))
        assertEquals(true, config.safeAreaDrawOutside)
        assertEquals(CarPlayClusterDisplay.MAP_URL, config.initialUrl)
        assertNull(ClusterMapPresentation.findDisplay(activity))
        assertEquals(CarPlayClusterDisplay.config(1280, 720, scale,
            baseSafeArea = CarPlayClusterDisplay.VIRTUAL_SAFE_AREA_PERCENT).safeArea, config.safeArea)
    }

    private fun decoder(maxWidth: Int, maxHeight: Int, maxFps: Double = 120.0, alignment: Int = 2, hardware: Boolean = true) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, maxWidth, maxHeight).apply {
            setString("alignment", "${alignment}x$alignment")
            setString("frame-rate-range", "1-${maxFps.toInt()}")
        }
        val level = MediaCodecInfo.CodecProfileLevel().apply {
            profile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
            this.level = MediaCodecInfo.CodecProfileLevel.AVCLevel52
        }
        val capabilities = MediaCodecInfoBuilder.CodecCapabilitiesBuilder.newBuilder()
            .setMediaFormat(format)
            .setColorFormats(intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface))
            .setProfileLevels(arrayOf(level)).build()
        val codec = MediaCodecInfoBuilder.newBuilder().setName("c2.test.hardware")
            .setIsHardwareAccelerated(hardware).setIsSoftwareOnly(!hardware).setCapabilities(capabilities).build()
        ShadowMediaCodecList.addCodec(codec)
    }

}
