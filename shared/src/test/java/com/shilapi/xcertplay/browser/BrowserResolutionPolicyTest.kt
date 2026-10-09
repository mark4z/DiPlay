package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.airplay.AirPlayDisplaySettings
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeBasis
import com.shilapi.xcertplay.airplay.AirPlayPhysicalSizeMm
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
        assertEquals(size(3840, 2160), BrowserResolutionPolicy.normalize(3840.0, 2160.0))
        assertEquals(size(2160, 3840), BrowserResolutionPolicy.normalize(2160.0, 3840.0))
        assertEquals(size(5760, 1920), BrowserResolutionPolicy.normalize(5760.0, 1920.0))
    }

    @Test fun everyAcceptedBaselinePreservesRawPixelsAndEveryOutputRespectsResourceBounds() {
        for (w in 320..16384 step 197) for (h in 320..16384 step 211) {
            val result = BrowserResolutionPolicy.normalize(w.toDouble(), h.toDouble()) ?: continue
            assertTrue(result.width >= 320 && result.height >= 320)
            assertTrue(maxOf(result.width, result.height) <= 16384)
            for (percent in listOf(30, 80, 100, 160)) for (ui in listOf(75, 85, 100, 115)) {
                val output = BrowserResolutionPolicy.outputSize(result, percent, ui)
                assertTrue(maxOf(output.width, output.height) <= 3840)
                assertTrue(minOf(output.width, output.height) <= 2160)
                assertTrue(output.width.toLong() * output.height <= 8_294_400L)
                assertEquals(0, output.width % 2)
                assertEquals(0, output.height % 2)
                assertTrue(output.width > 0 && output.height > 0)
            }
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
        assertNotEquals(a, b)
        val outputA = BrowserResolutionPolicy.outputSize(a, 100, 100)
        val outputB = BrowserResolutionPolicy.outputSize(b, 100, 100)
        assertEquals(outputA, outputB)
        val gate = BrowserResolutionReconnectGate()
        assertFalse(gate.shouldReconnect(outputA, outputB, 0))
        assertFalse(gate.shouldReconnect(outputB, outputA, 100000))
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
    @Test fun highDpiTabletBaselineKeepsDetailAndAppliesPercentageBeforeFinalCap() {
        val baseline = BrowserResolutionPolicy.normalize(3200.0, 2136.0)!!
        assertEquals(size(3200, 2136), baseline)
        assertEquals(size(2560, 1710), BrowserResolutionPolicy.outputSize(baseline, 80, 100))
        assertEquals(size(3200, 2136), BrowserResolutionPolicy.outputSize(baseline, 100, 100))
        assertEquals(size(3234, 2160), BrowserResolutionPolicy.outputSize(baseline, 160, 100))
        // No mutation of the baseline or user percentage occurs when the output hits its ceiling.
        assertEquals(size(3200, 2136), baseline)
        assertEquals(size(3200, 2136), BrowserResolutionPolicy.outputSize(baseline, 100, 100))
    }

    @Test fun finalCapKeepsPortraitAndNarrowBrowserShape() {
        assertEquals(size(2160, 3234), BrowserResolutionPolicy.outputSize(size(2136, 3200), 160, 100))
        assertEquals(size(3840, 1280), BrowserResolutionPolicy.outputSize(size(5760, 1920), 100, 100))
        assertEquals(size(1280, 3840), BrowserResolutionPolicy.outputSize(size(1920, 5760), 100, 100))
        assertEquals(size(96, 288), BrowserResolutionPolicy.outputSize(size(320, 960), 30, 100))
    }

    @Test fun uiScaleAppliesBeforeCapWithoutFallingBackToAndroidShape() {
        assertEquals(size(2134, 1600), BrowserResolutionPolicy.outputSize(size(1600, 1200), 100, 75))
        assertEquals(size(2878, 2160), BrowserResolutionPolicy.outputSize(size(3200, 2400), 160, 75))
    }

    @Test fun browserPhysicalReferenceIsIndependentOfDensityAndQuality() {
        for (basis in AirPlayPhysicalSizeBasis.values()) {
            val full = AirPlayDisplaySettings.resolveBrowserPhysicalSizeMm(3200, 2136, 200, basis)
            val lowerDensity = AirPlayDisplaySettings.resolveBrowserPhysicalSizeMm(1600, 1068, 200, basis)
            assertEquals(full, lowerDensity)
            for (percent in listOf(80, 100, 160)) {
                val target = BrowserResolutionPolicy.outputSize(size(3200, 2136), percent, 100)
                val config = AirPlayDisplayConfig(target.width, target.height,
                    widthPhysicalMm = full.widthMm, heightPhysicalMm = full.heightMm)
                assertEquals(full.widthMm, requireNotNull(config.widthPhysicalMm))
                assertEquals(full.heightMm, requireNotNull(config.heightPhysicalMm))
            }
        }
        assertEquals(AirPlayPhysicalSizeMm(200, 300),
            AirPlayDisplaySettings.resolveBrowserPhysicalSizeMm(2136, 3200, 200, AirPlayPhysicalSizeBasis.WIDTH))
        assertEquals(AirPlayPhysicalSizeMm(134, 200),
            AirPlayDisplaySettings.resolveBrowserPhysicalSizeMm(2136, 3200, 200, AirPlayPhysicalSizeBasis.HEIGHT))
    }

    @Test(expected = IllegalArgumentException::class)
    fun outputRejectsUnvalidatedBaseline() {
        BrowserResolutionPolicy.outputSize(size(16386, 16386), 100, 100)
    }

}
