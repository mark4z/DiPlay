package com.shilapi.xcertplay.generic

import android.view.KeyEvent
import android.content.pm.PackageManager
import android.content.res.Configuration
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GenericInputTest {
    @Test fun inputCapabilitiesPreferKnobForTelevisionOrNoTouch() {
        val context = RuntimeEnvironment.getApplication()
        context.resources.configuration.touchscreen = Configuration.TOUCHSCREEN_FINGER
        assertFalse(GenericCapabilities.knobPrimary(context))
        context.resources.configuration.touchscreen = Configuration.TOUCHSCREEN_NOTOUCH
        assertTrue(GenericCapabilities.knobPrimary(context))
        context.resources.configuration.touchscreen = Configuration.TOUCHSCREEN_FINGER
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)
        assertTrue(GenericCapabilities.knobPrimary(context))
    }

    @Test fun standardPlayAndPauseRetainTheirMeaningAndDoNotRepeat() {
        val reports = mutableListOf<Int>()
        fun dispatch(event: KeyEvent) = GenericInput.key(event, { reports.add(it); true }, { false }, { _, _ -> false })
        assertTrue(dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY)))
        assertTrue(dispatch(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY)))
        assertTrue(dispatch(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE)))
        assertTrue(dispatch(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 1)))
        assertEquals(listOf(CarPlayMediaButton.PLAY, CarPlayMediaButton.PAUSE), reports)
    }

    @Test fun dpadMapsToWheelAndHeldButtonsHaveARealRelease() {
        val reports = mutableListOf<Pair<AirPlayKnobState, Boolean>>()
        fun dispatch(key: Int, action: Int = KeyEvent.ACTION_DOWN) = GenericInput.key(
            KeyEvent(action, key), { false }, { false }, { state, momentary -> reports.add(state to momentary); true },
        )
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_LEFT))
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_CENTER))
        assertTrue(dispatch(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.ACTION_UP))
        assertTrue(dispatch(KeyEvent.KEYCODE_BACK))
        assertTrue(dispatch(KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP))
        assertEquals(listOf(
            AirPlayKnobState(wheel = -1) to true, AirPlayKnobState(wheel = 1) to true,
            AirPlayKnobState(select = true) to false, AirPlayKnobState() to false,
            AirPlayKnobState(back = true) to false, AirPlayKnobState() to false,
        ), reports)
    }

    @Test fun voiceAssistDoesNotRepeatAndUnknownVendorCodesAreIgnored() {
        var requests = 0
        assertTrue(GenericInput.key(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOICE_ASSIST), { false }, { requests++; true }, { _, _ -> false }))
        assertTrue(GenericInput.key(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOICE_ASSIST), { false }, { requests++; true }, { _, _ -> false }))
        assertEquals(1, requests)
        for (key in listOf(304, 312, 327, 328, 331, 353)) {
            assertFalse(GenericInput.key(KeyEvent(KeyEvent.ACTION_DOWN, key), { true }, { true }, { _, _ -> true }))
        }
    }
}
