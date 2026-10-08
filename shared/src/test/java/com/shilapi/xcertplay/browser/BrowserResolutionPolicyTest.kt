package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import org.junit.Assert.*
import org.junit.Test

class BrowserResolutionPolicyTest {
    private fun size(w: Int, h: Int) = BrowserResolutionPolicy.Size(w, h)

    @Test fun rejectsMalformedAndExtremeDimensions() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 319.0, 800.5, 16385.0)) {
            assertNull(BrowserResolutionPolicy.normalize(value, 720.0))
            assertNull(BrowserResolutionPolicy.normalize(1280.0, value))
        }
        assertNull(BrowserResolutionPolicy.normalize(1920.0, 320.0))
    }

    @Test fun alignsWithoutUpscalingAndPreservesOrientation() {
        assertEquals(size(1280, 720), BrowserResolutionPolicy.normalize(1281.0, 721.0))
        assertEquals(size(320, 480), BrowserResolutionPolicy.normalize(320.0, 480.0))
        assertEquals(size(1920, 1080), BrowserResolutionPolicy.normalize(3840.0, 2160.0))
        assertEquals(size(1080, 1920), BrowserResolutionPolicy.normalize(2160.0, 3840.0))
        assertEquals(size(1920, 640), BrowserResolutionPolicy.normalize(5760.0, 1920.0))
    }

    @Test fun everyAcceptedSizeRespectsResourceBounds() {
        for (w in 320..16384 step 197) for (h in 320..16384 step 211) {
            val result = BrowserResolutionPolicy.normalize(w.toDouble(), h.toDouble()) ?: continue
            assertTrue(result.width >= 320 && result.height >= 320)
            assertTrue(maxOf(result.width, result.height) <= 1920)
            assertTrue(minOf(result.width, result.height) <= 1080)
            assertTrue(result.width.toLong() * result.height <= 2_073_600L)
            assertEquals(0, result.width % 2)
            assertEquals(0, result.height % 2)
            assertEquals(result, BrowserResolutionPolicy.normalize(result.width.toDouble(), result.height.toDouble()))
        }
    }

    @Test fun stableFinalOutputReconnectsOnceAndNeverReenters() {
        val gate = BrowserResolutionReconnectGate()
        val active = size(1280, 720)
        val target = size(1536, 864) // 1920x1080 baseline at the existing 80% setting.
        assertFalse(gate.shouldReconnect(target, active, 0))
        assertFalse(gate.shouldReconnect(target, active, 1499))
        assertTrue(gate.shouldReconnect(target, active, 1500))
        assertFalse(gate.shouldReconnect(target, active, 50000))
        gate.complete(true)
        assertFalse(gate.shouldReconnect(target, target, 60000))
        assertFalse(gate.shouldReconnect(target, active, 70000))
    }

    @Test fun eightyAndHundredPercentUnchangedFinalOutputsNeverReconnect() {
        val baseline = BrowserResolutionPolicy.normalize(1920.0, 1080.0)!!
        for ((percent, expected) in listOf(80 to size(1536, 864), 100 to size(1920, 1080))) {
            val display = CarPlayDisplayScale.applyPercent(AirPlayDisplayConfig(baseline.width, baseline.height), percent)
            val target = size(display.widthPixels, display.heightPixels)
            assertEquals(expected, target)
            val gate = BrowserResolutionReconnectGate()
            assertFalse(gate.shouldReconnect(target, target, 0))
            assertFalse(gate.shouldReconnect(target, target, 100000))
        }
    }

    @Test fun distinctRawViewportsWithSameClampedEffectiveOutputDoNotReconnect() {
        val a = BrowserResolutionPolicy.normalize(3840.0, 2160.0)!!
        val b = BrowserResolutionPolicy.normalize(7680.0, 4320.0)!!
        assertEquals(a, b)
        val gate = BrowserResolutionReconnectGate()
        assertFalse(gate.shouldReconnect(a, b, 0))
        assertFalse(gate.shouldReconnect(b, a, 100000))
    }

    @Test fun resizeRestartsStabilityAndCooldownIsGlobal() {
        val gate = BrowserResolutionReconnectGate()
        val active = size(1280, 720)
        val a = size(1600, 900)
        val b = size(1920, 1080)
        assertFalse(gate.shouldReconnect(a, active, 0))
        assertFalse(gate.shouldReconnect(b, active, 1000))
        assertFalse(gate.shouldReconnect(b, active, 2499))
        assertTrue(gate.shouldReconnect(b, active, 2500))
        gate.complete(true)
        assertFalse(gate.shouldReconnect(a, b, 3000))
        assertFalse(gate.shouldReconnect(a, b, 17499))
        assertTrue(gate.shouldReconnect(a, b, 17500))
    }

    @Test fun terminalFailureStopsAllAutomaticAttemptsUntilManualReset() {
        val gate = BrowserResolutionReconnectGate()
        val active = size(1280, 720)
        val target = size(1920, 1080)
        assertFalse(gate.shouldReconnect(target, active, 0))
        assertTrue(gate.shouldReconnect(target, active, 1500))
        gate.complete(false)
        assertTrue(gate.failureBlocked)
        assertFalse(gate.shouldReconnect(size(1600, 900), active, 100000))
        assertFalse(gate.shouldReconnect(size(1600, 900), active, 200000))
        gate.reset()
        assertFalse(gate.shouldReconnect(target, active, 200000))
        assertTrue(gate.shouldReconnect(target, active, 201500))
    }

    @Test fun disallowedAndBackwardTimeCannotTrigger() {
        val gate = BrowserResolutionReconnectGate()
        val target = size(1920, 1080)
        assertFalse(gate.shouldReconnect(target, null, 10000, false))
        assertFalse(gate.shouldReconnect(target, null, 9999))
        assertFalse(gate.shouldReconnect(target, null, 20000, false))
        assertTrue(gate.shouldReconnect(target, null, 20000))
    }
}
