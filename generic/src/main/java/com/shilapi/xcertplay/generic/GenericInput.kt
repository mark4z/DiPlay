package com.shilapi.xcertplay.generic

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.shilapi.xcertplay.airplay.AirPlayKnobState
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import kotlin.math.roundToInt

/** Android's documented media, navigation and rotary inputs only. */
internal object GenericInput {
    fun key(
        event: KeyEvent,
        media: (Int) -> Boolean,
        siri: () -> Boolean,
        knob: (AirPlayKnobState, Boolean) -> Boolean,
    ): Boolean {
        CarPlayMediaButton.forKeyCode(event.keyCode)?.let { index ->
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) return media(index)
            return event.action == KeyEvent.ACTION_UP || event.action == KeyEvent.ACTION_DOWN
        }
        if (event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            return if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) siri()
            else event.action == KeyEvent.ACTION_UP || event.action == KeyEvent.ACTION_DOWN
        }
        val wheel = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> 0
        }
        if (wheel != 0) return when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount % 3 == 0) knob(AirPlayKnobState(wheel = wheel), true) else true
            KeyEvent.ACTION_UP -> true
            else -> false
        }
        val state = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_SELECT -> AirPlayKnobState(select = true)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B -> AirPlayKnobState(back = true)
            else -> return false
        }
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) knob(state, false) else true
            KeyEvent.ACTION_UP -> knob(AirPlayKnobState(), false)
            else -> false
        }
    }

    fun rotary(event: MotionEvent, knob: (AirPlayKnobState, Boolean) -> Boolean): Boolean {
        if (event.action != MotionEvent.ACTION_SCROLL || !event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) return false
        val scroll = event.getAxisValue(MotionEvent.AXIS_SCROLL)
        if (!scroll.isFinite() || scroll == 0f) return false
        val steps = scroll.roundToInt().let { if (it == 0) if (scroll > 0) 1 else -1 else it }.coerceIn(-8, 8)
        return knob(AirPlayKnobState(wheel = steps), true)
    }
}
