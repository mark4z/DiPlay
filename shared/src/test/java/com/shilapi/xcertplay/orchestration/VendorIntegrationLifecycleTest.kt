package com.shilapi.xcertplay.orchestration

import android.content.Context
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.platform.CarPlayVendorIntegration
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Runs against both flavors; an injected output is the controller's only vendor dependency. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VendorIntegrationLifecycleTest {
    @Test fun routesFramesAndClosesOutputExactlyOnce() {
        val events = mutableListOf<String>()
        var streamControl: ((Boolean) -> Unit)? = null
        val frame = Iap2Frame(0x1234, byteArrayOf())
        val integration = object : CarPlayVendorIntegration {
            override fun start(context: Context) { events += "start" }
            override fun setClusterStreamControl(control: (Boolean) -> Unit) {
                streamControl = control
                events += "set-control"
            }
            override fun onFrame(received: Iap2Frame) {
                assertSame(frame, received)
                events += "frame"
            }
            override fun endNow(preserveTurnOverlay: Boolean) {
                assertFalse(preserveTurnOverlay)
                events += "end"
            }
            override fun clearClusterStreamControl(control: (Boolean) -> Unit) {
                assertEquals(streamControl, control)
                events += "clear-control"
            }
        }
        val controller = CarPlayController(
            context = RuntimeEnvironment.getApplication(),
            config = CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig("test", "test", "test", "test", "1", "1", 3),
            ),
            airPlayConfig = AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
            identity = AirPlayIdentity(ByteArray(32), ByteArray(32), "test"),
            pairings = PairingStore(),
            listener = object : AirPlaySessionListener {},
            media = object : AirPlayMediaHandler {},
            reportStatus = {},
            vendorIntegration = integration,
        )
        try {
            assertEquals(listOf("start", "set-control"), events)
            CarPlayController::class.java.getDeclaredMethod("onRouteFrame", Iap2Frame::class.java)
                .apply { isAccessible = true }.invoke(controller, frame)
            controller.close()
            assertTrue(controller.awaitClosed(2000))
            controller.close()
            assertEquals(listOf("start", "set-control", "frame", "end", "clear-control"), events)
        } finally {
            controller.close()
            assertTrue(controller.awaitClosed(2000))
        }
    }
}
