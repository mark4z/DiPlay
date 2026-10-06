package com.shilapi.xcertplay.platform

import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test

class GenericIntegrationTest {
    @Test fun defaultHasNoVehicleOutputs() {
        assertSame(NoVendorIntegration, DefaultVendorIntegration.create())
    }

    @Test fun vendorClassIsNotOnTheGenericRuntimeClasspath() {
        assertTrue(runCatching {
            Class.forName("com.shilapi.xcertplay.hud.BydNavigationOutputs")
        }.exceptionOrNull() is ClassNotFoundException)
    }

    @Test fun optionalShellEnhancementIsOffByDefault() {
        val config = CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL,
            identification = Iap2IdentificationConfig(
                "Generic", "Android", "Android", "TEST", "1", "1", 3,
            ),
        )
        assertFalse(config.allowAdbWifiScanPause)
        assertFalse(config.identification.vehicleStatusEnabled)
        assertFalse(config.identification.vehicleSpeedEnabled)
    }

    @Test fun standardMediaCommandsRemainDistinct() {
        assertEquals(CarPlayMediaButton.PLAY, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY))
        assertEquals(CarPlayMediaButton.PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        assertEquals(CarPlayMediaButton.PLAY_PAUSE, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_HEADSETHOOK))
        assertEquals(CarPlayMediaButton.NEXT, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(CarPlayMediaButton.PREVIOUS, CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertTrue(CarPlayMediaButton.opensSiri(KeyEvent.KEYCODE_VOICE_ASSIST))
    }

    @Test fun unknownManufacturerKeysAreNotConsumed() {
        for (key in listOf(304, 312, 327, 328, 331, 353)) {
            assertNull(CarPlayMediaButton.forKeyCode(key))
            assertFalse(CarPlayMediaButton.opensSiri(key))
        }
        assertNull(CarPlayMediaButton.forKeyCode(KeyEvent.KEYCODE_VOLUME_UP))
    }
}
