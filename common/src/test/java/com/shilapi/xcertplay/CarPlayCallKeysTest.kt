package com.shilapi.xcertplay

import android.view.KeyEvent
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.hud.BydOutputSettings
import com.shilapi.xcertplay.hud.CarPlayCallState
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayCallKeysTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private lateinit var controller: CarPlayController

    @Before fun setup() {
        controller = mock(CarPlayController::class.java)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(Any())
        `when`(controller.answerCall()).thenReturn(true)
        `when`(controller.endCall()).thenReturn(true)
        BydOutputSettings.setCarPlayCallControls(app, false)
        callState().clear()
        callState().accept(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            string(1, "Caller"); u8(2, 2); string(4, "standard-call-key-test")
        })
    }

    @After fun cleanup() {
        callState().clear()
        BydOutputSettings.setCarPlayCallControls(app, false)
    }

    private fun callState(): CarPlayCallState =
        ReflectionHelpers.getField(BydNavigationOutputs, "callState")

    @Test fun standardCallKeysStillRequireTheOriginalOptIn() {
        for (key in listOf(KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL)) {
            assertFalse(CarPlayCallKeys.onKey(app, key, true, controller))
            assertFalse(CarPlayCallKeys.onKey(app, key, false, controller))
        }
        verify(controller, never()).answerCall()
        verify(controller, never()).endCall()
    }

    @Test fun standardAndroidCallAndEndCallReachThePhoneOnceOnRelease() {
        BydOutputSettings.setCarPlayCallControls(app, true)
        for (key in listOf(KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL)) {
            assertTrue(CarPlayCallKeys.onKey(app, key, true, controller))
            assertTrue(CarPlayCallKeys.onKey(app, key, false, controller))
        }
        verify(controller, times(1)).answerCall()
        verify(controller, times(1)).endCall()
    }

    @Test fun proprietaryCodesStayDisconnectedEvenWithSavedOptIn() {
        BydOutputSettings.setCarPlayCallControls(app, true)
        for (key in listOf(309, 313, 314)) {
            assertFalse(CarPlayCallKeys.onKey(app, key, true, controller))
            assertFalse(CarPlayCallKeys.onKey(app, key, false, controller))
        }
        verify(controller, never()).answerCall()
        verify(controller, never()).endCall()
        CarPlayCallKeys.install(app)
        assertFalse(ReflectionHelpers.getField(CarPlayCallKeys, "installed"))
    }

    @Test fun disconnectedSessionLeavesStandardCallsWithAndroid() {
        BydOutputSettings.setCarPlayCallControls(app, true)
        `when`(controller.activeAirPlaySessionToken()).thenReturn(null)
        assertFalse(CarPlayCallKeys.onKey(app, KeyEvent.KEYCODE_CALL, false, controller))
        assertFalse(CarPlayCallKeys.onKey(app, KeyEvent.KEYCODE_ENDCALL, false, controller))
        verify(controller, never()).answerCall()
        verify(controller, never()).endCall()
    }

}
