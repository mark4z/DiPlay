package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import com.shilapi.xcertplay.browser.BrowserResolutionPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserResolutionCoordinatorTest {
    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val coordinator = BrowserResolutionCoordinator(activity)
    private val replies = mutableListOf<JSONObject>()
    private var active: BrowserResolutionPolicy.Size? = BrowserResolutionPolicy.Size(1280, 720)
    private var restarts = 0

    @After fun cleanup() {
        coordinator.uninstall(this)
        File(activity.noBackupFilesDir, "browser-resolution.json").delete()
        File(activity.noBackupFilesDir, "browser-resolution.json.bak").delete()
    }

    private fun install(percent: Int = 100) {
        coordinator.install(this, { base ->
            val selected = base ?: BrowserResolutionPolicy.Size(1280, 720)
            val scaled = com.shilapi.xcertplay.airplay.CarPlayDisplayScale.applyPercent(
                com.shilapi.xcertplay.airplay.AirPlayDisplayConfig(selected.width, selected.height), percent)
            BrowserResolutionCoordinator.Evaluation(
                BrowserResolutionPolicy.Size(scaled.widthPixels, scaled.heightPixels), active, true)
        }, { restarts++; true })
    }

    private fun request(id: Int = 1, width: Any = 1920, height: Any = 1080) {
        coordinator.request(JSONObject().put("requestId", id).put("enabled", true).put("units", BrowserResolutionPolicy.UNITS)
            .put("width", width).put("height", height), { replies.add(it) }, { true })
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun rejectsAmbiguousUnitsAndDprWithoutChangingSavedBaseline() {
        val before = BrowserResolutionPolicy.Size(1600, 900)
        assertTrue(coordinator.save(before))
        for (unit in listOf(null, "css-pixels", "physical-panel")) {
            val message = JSONObject().put("requestId", 1).put("enabled", true)
                .put("width", 1920).put("height", 1080)
            unit?.let { message.put("units", it) }
            coordinator.request(message, { replies.add(it) }, { true })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("invalidDimensions", replies.last().getString("code"))
            assertEquals(before, coordinator.load())
        }
        coordinator.request(JSONObject().put("requestId", 2).put("enabled", true)
            .put("width", 1920).put("height", 1080).put("units", BrowserResolutionPolicy.UNITS).put("dpr", 2),
            { replies.add(it) }, { true })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("invalidDimensions", replies.last().getString("code"))
        assertEquals(before, coordinator.load())
    }

    @Test fun legacyCssBaselineIsNotMisreadAsDevicePixels() {
        File(activity.noBackupFilesDir, "browser-resolution.json").writeText(
            """{"enabled":true,"width":1280,"height":720}""")
        assertNull(coordinator.load())
        request(width = 1920, height = 1080)
        assertEquals(BrowserResolutionPolicy.Size(1920, 1080), coordinator.load())
        assertEquals("device-pixels", replies.last().getString("units"))
    }

    @Test fun persistsBoundedBaselineWithoutChangingPercentageAndReconnectsOnce() {
        install(80)
        request()
        assertEquals(BrowserResolutionPolicy.Size(1920, 1080), coordinator.load())
        assertEquals(0, restarts)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        assertEquals(1, restarts)
        assertEquals(1536, replies.last().getInt("effectiveWidth"))
        active = BrowserResolutionPolicy.Size(1536, 864)
        coordinator.complete(true)
        request(2)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals(1, restarts)
        assertEquals("unchanged", replies.last().getString("applies"))
    }

    @Test fun rejectsStringDimensionsAndKeepsPreviousSavedSetting() {
        install()
        val before = BrowserResolutionPolicy.Size(1600, 900)
        assertTrue(coordinator.save(before))
        request(width = "1920")
        assertEquals("invalidDimensions", replies.last().getString("code"))
        assertEquals(before, coordinator.load())
        assertEquals(0, restarts)
    }

    @Test fun timeoutStopsAutomaticRetryEvenWhenViewportChanges() {
        install()
        request()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(32))
        assertEquals(1, restarts)
        assertEquals("reconnectFailed", replies.last().getString("code"))
        request(2, 1600, 900)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals(1, restarts)
    }

    @Test fun ownerDisposalStillAllowsAuthenticatedSaveForNextConnection() {
        install()
        coordinator.request(JSONObject().put("requestId", 1).put("enabled", true).put("units", BrowserResolutionPolicy.UNITS)
            .put("width", 1920).put("height", 1080), { replies.add(it) }, { true })
        coordinator.uninstall(this)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals(0, restarts)
        assertEquals("nextConnection", replies.last().getString("applies"))
        assertEquals(BrowserResolutionPolicy.Size(1920, 1080), coordinator.load())
    }

    @Test fun corruptFileAndExplicitResetUseNormalDisplay() {
        File(activity.noBackupFilesDir, "browser-resolution.json").writeText("{broken")
        assertNull(coordinator.load())
        assertTrue(coordinator.save(BrowserResolutionPolicy.Size(1920, 1080)))
        assertTrue(coordinator.save(null))
        assertNull(coordinator.load())
    }

    @Test fun staleViewerCannotSaveOrTriggerQueuedReconnect() {
        install()
        var current = true
        val message = JSONObject().put("requestId", 1).put("enabled", true).put("units", BrowserResolutionPolicy.UNITS)
            .put("width", 1920).put("height", 1080)
        coordinator.request(message, { replies.add(it) }, { current })
        current = false
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(coordinator.load())
        assertTrue(replies.isEmpty())
        current = true
        coordinator.request(message, { replies.add(it) }, { current })
        shadowOf(Looper.getMainLooper()).idle()
        current = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals(0, restarts)
    }

    @Test fun connectedWithWrongOutputDoesNotCountAsSuccessfulResize() {
        install()
        request()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        coordinator.complete(true)
        assertEquals("reconnectFailed", replies.last().getString("code"))
        request(2, 1600, 900)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
        assertEquals(1, restarts)
    }

    @Test fun approvedBrowserBeforeHostExistsPersistsAndAcknowledges() {
        request()
        assertEquals(BrowserResolutionPolicy.Size(1920, 1080), coordinator.load())
        assertEquals("nextConnection", replies.last().getString("applies"))
        assertTrue(replies.last().getBoolean("enabled"))
        assertEquals(0, restarts)
    }

    @Test fun invalidDimensionsUsesFullErrorEnvelope() {
        request(width = "bad")
        assertEquals("invalidDimensions", replies.last().getString("code"))
        assertEquals("nextConnection", replies.last().getString("applies"))
        assertFalse(replies.last().getBoolean("enabled"))
    }

    @Test fun newerSavedTargetNegotiatedDuringTeardownCountsAsSuccess() {
        install()
        request()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        active = null
        request(2, 1600, 900)
        active = BrowserResolutionPolicy.Size(1600, 900)
        coordinator.complete(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, replies.last().getInt("requestId"))
        assertEquals("unchanged", replies.last().getString("applies"))
        request(3)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertEquals(2, restarts)
    }

    @Test fun resetWhileAttemptIsInFlightSurvivesFailure() {
        install()
        request()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500))
        active = null
        coordinator.request(JSONObject().put("requestId", 2).put("enabled", false),
            { replies.add(it) }, { true })
        shadowOf(Looper.getMainLooper()).idle()
        active = BrowserResolutionPolicy.Size(1280, 720)
        coordinator.complete(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, replies.last().getInt("requestId"))
        assertEquals("unchanged", replies.last().getString("applies"))
        assertNull(coordinator.load())
        request(3)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16))
        assertEquals(2, restarts)
    }
}

