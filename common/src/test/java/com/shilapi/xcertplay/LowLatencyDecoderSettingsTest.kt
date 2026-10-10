package com.shilapi.xcertplay

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30], qualifiers = "en", manifest = Config.NONE)
class LowLatencyDecoderSettingsTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() { app.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit() }

    @Test fun absentPreferenceIsOffAndRoundTripDoesNotChangeExistingPreferences() {
        assertFalse(AirPlayPersistence.loadLowLatencyDecoder(app))
        AirPlayPersistence.saveHevcEnabled(app, true)
        AirPlayPersistence.savePerformanceDiagnosticsEnabled(app, true)
        AirPlayPersistence.saveLowLatencyDecoder(app, true)
        assertTrue(AirPlayPersistence.loadLowLatencyDecoder(app))
        AirPlayPersistence.saveLowLatencyDecoder(app, false)
        assertFalse(AirPlayPersistence.loadLowLatencyDecoder(app))
        assertTrue(AirPlayPersistence.loadHevcEnabled(app))
        assertTrue(AirPlayPersistence.loadPerformanceDiagnosticsEnabled(app))
    }

    @Test fun settingCanBeEnabledAndDisabledWithoutStartingAnActivityOrConnection() {
        val activity = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        activity.setTheme(android.R.style.Theme_Material_NoActionBar)
        val card = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("lowLatencyDecoderControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, card)
        val control = descendants(card).filterIsInstance<Switch>().single()
        assertFalse(control.isChecked)
        control.isChecked = true
        assertTrue(AirPlayPersistence.loadLowLatencyDecoder(app))
        control.isChecked = false
        assertFalse(AirPlayPersistence.loadLowLatencyDecoder(app))
        assertNull(org.robolectric.Shadows.shadowOf(activity).nextStartedActivity)
    }

    @Test fun changingThePreferenceOnlyAffectsANewMediaSink() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val create = CarPlayHostActivity::class.java.getDeclaredMethod("createMediaSink",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val first = create.invoke(host, 800, 480, 0) as com.shilapi.xcertplay.media.AndroidMediaSink
        var second: com.shilapi.xcertplay.media.AndroidMediaSink? = null
        try {
            AirPlayPersistence.saveLowLatencyDecoder(app, true)
            second = create.invoke(host, 800, 480, 1) as com.shilapi.xcertplay.media.AndroidMediaSink
            val option = first.javaClass.getDeclaredField("vendorLowLatencyDecoder").apply { isAccessible = true }
            assertEquals(false, option.get(first))
            assertEquals(true, option.get(second))
            AirPlayPersistence.saveLowLatencyDecoder(app, false)
            assertEquals(true, option.get(second))
        } finally {
            first.close()
            second?.close()
        }
    }

    @Test fun descriptionStatesExperimentalNativeOnlyNextConnectionScope() {
        assertTrue(app.getString(R.string.settings_qualcomm_low_latency_decoder).contains("experimental"))
        val description = app.getString(R.string.settings_qualcomm_low_latency_decoder_description)
        assertTrue(description.contains("main picture"))
        assertTrue(description.contains("no effect on browser"))
        assertTrue(description.contains("next connection"))
        assertTrue(description.contains("Turn off"))
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
