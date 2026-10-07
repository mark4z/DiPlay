package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.pm.PackageInfo
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.airplay.VideoInCar
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.orchestration.VideoInCarGate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBuild
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** Live integration boundaries must stay disconnected even after upgrading an opted-in BYD install. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE,
    shadows = [BydHardwareDisconnectionTest.Adb::class, BydHardwareDisconnectionTest.Shell::class])
class BydHardwareDisconnectionTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        BydNavigationOutputs.endNow()
        BydNavigationOutputs.setTurnOverlayListener(null)
        app.getSharedPreferences("diplay_byd_outputs", 0).edit().clear()
            .putBoolean("navigation_enabled", true)
            .putBoolean("battery_to_iphone", true)
            .putBoolean("wheel_speed_to_iphone", true)
            .putBoolean("video_while_parked", true)
            .putBoolean("legacy_vehicle_probe", true)
            .putBoolean("cluster_song", true)
            .putBoolean("hud_song", true)
            .putBoolean("carplay_calls", true)
            .putBoolean("cluster_stream_pause", true).commit()
        Adb.connections = 0
        Shell.commands.clear()
    }

    @After fun cleanup() {
        BydNavigationOutputs.endNow()
        BydNavigationOutputs.setTurnOverlayListener(null)
        VideoInCar.allowed = false
    }

    @Test fun savedOptInsAndDetectedHardwareCannotEnableVehicleOutputs() {
        ShadowBuild.setBrand("BYD")
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = "com.ts.car.someip.service" })
        assertFalse(BydHardwareIntegration.ENABLED)
        // Keep old preferences and read-only eligibility metadata without activating hardware.
        assertTrue(BydOutputSettings.batteryToIphone(app))
        assertTrue(BydOutputSettings.wheelSpeedToIphone(app))
        assertTrue(BydOutputSettings.videoWhileParked(app))
        assertTrue(BydOutputSettings.navigationHardwareDetected(app))
        assertFalse(BydOutputSettings.available(app))
        assertFalse(BydOutputSettings.navigationAvailable(app))
        assertFalse(BydOutputSettings.standaloneHudAvailable(app))
        assertFalse(BydOutputSettings.batteryToIphoneActive(app))
        assertFalse(BydOutputSettings.wheelSpeedToIphoneActive(app))
        assertFalse(BydOutputSettings.videoWhileParkedActive(app))
        assertNull(BydStandaloneHudOutput.create(app))
        assertNoHardware()
    }

    @Test fun checksAndProviderRequestsReturnUnknownWithoutOpeningAdb() {
        for (mayAsk in listOf(false, true)) {
            val status = BydAdbAccess.check(app, mayAsk)
            assertEquals(BydAdbAccess.State.DISABLED, status.state)
            assertNull(status.batteryPercent)
            assertNull(status.speedKmh)
            assertNull(status.gear)
        }
        for (persist in listOf(false, true)) {
            val probe = BydVehicleCapabilityProbe.probe(app, persist)
            assertEquals(BydAdbAccess.State.DISABLED, probe.access)
            assertNull(probe.capabilities)
        }
        assertNull(BydNavigationOutputs.batteryStatus(app).snapshot())
        val speed = BydNavigationOutputs.wheelSpeed(app)
        speed.start()
        assertNull(speed.drain())
        speed.stop()
        assertNull(BydNavigationOutputs.parked(app))
        val allowed = mutableListOf<Boolean>()
        val gate = VideoInCarGate({ BydNavigationOutputs.parked(app) }, allowed::add)
        try {
            gate.update(true)
            gate.update(BydNavigationOutputs.parked(app))
            assertEquals(listOf(true, false), allowed)
            assertFalse(VideoInCar.allowed)
        } finally { gate.close() }
        assertNoHardware()
    }

    @Test fun appOpenAndOldRecoveryJournalsCannotRestoreProprietaryServices() {
        val oem = app.getSharedPreferences("diplay_oem_cluster", 0)
        val dilink = app.getSharedPreferences("diplay_dilink3_cluster", 0)
        val hud = app.getSharedPreferences("byd_standalone_hud", 0)
        oem.edit().putString("restore_journal", "PACKAGE:0:old-route").commit()
        dilink.edit().putBoolean("restore_stock_mode", true).commit()
        hud.edit().putBoolean("pending_clear", true).commit()
        val original = listOf(oem.all, dilink.all, hud.all)
        BydNavigationOutputs.onAppOpened(app)
        BydNavigationOutputs.start(app)
        BydOemClusterNavi.restoreIfNeeded(app)
        assertFalse(BydOemClusterNavi.holdForLaunch(app, "new-route") { true })
        BydOemClusterNavi.release(app)
        BydDiLink3ClusterOutput.restoreIfNeeded(app)
        BydDiLink3ClusterOutput.setDesired(app, mapShown = true, guidanceActive = true)
        var displayQueried = false
        BydDiLink3ClusterOutput.prepareDisplay(app) { displayQueried = true; true }
        BydStarterBridge.initialize(app)
        for (owner in listOf(BydOemClusterNavi, BydDiLink3ClusterOutput)) {
            ReflectionHelpers.getField<ExecutorService>(owner, "worker").submit {}.get(5, TimeUnit.SECONDS)
        }
        assertFalse(displayQueried)
        assertEquals(original, listOf(oem.all, dilink.all, hud.all))
        assertNoHardware()
    }

    @Test fun protocolNavigationSongCallAndSessionCleanupRemainAvailable() {
        val overlays = mutableListOf<ClusterTurnGuidance?>()
        BydNavigationOutputs.start(app)
        BydNavigationOutputs.setTurnOverlayListener(overlays::add)
        BydNavigationOutputs.onFrame(Iap2Messages.buildRaw(BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) {
            u16(1, 7); u8(3, 1)
        })
        BydNavigationOutputs.onFrame(Iap2Messages.buildRaw(BydHudRouteState.ROUTE_GUIDANCE_UPDATE) {
            u8(1, 1); u32(10, 150); u16(13, 7)
        })
        assertEquals(150, overlays.last()!!.distanceMeters)
        BydNavigationOutputs.onFrame(Iap2Messages.buildRaw(ClusterSongState.NOW_PLAYING_UPDATE) {
            group(0) { string(1, "Track"); string(12, "Artist") }
            group(1) { u8(0, 1) }
        })
        val song = ReflectionHelpers.getField<ClusterSongState>(BydNavigationOutputs, "songState")
        assertEquals(ClusterSong("Track — Artist", true, "Track"), song.current())
        BydNavigationOutputs.onFrame(Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            string(1, "Caller"); u8(2, 2); string(4, "session-call")
        })
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Caller"), BydNavigationOutputs.carPlayCall())
        BydNavigationOutputs.clusterSongChanged(true)
        BydNavigationOutputs.carPlayCallsChanged(true)
        BydNavigationOutputs.clusterSongOnChangeChanged()
        BydNavigationOutputs.dashboardNote("Generic map notice")
        val streamChanges = mutableListOf<Boolean>()
        val stream: (Boolean) -> Unit = streamChanges::add
        BydNavigationOutputs.setClusterStreamControl(stream)
        BydNavigationOutputs.setClusterMapShown(false)
        BydNavigationOutputs.setClusterMapShown(true)
        BydNavigationOutputs.clearClusterStreamControl(stream)
        assertTrue(streamChanges.isEmpty())
        BydNavigationOutputs.endNow(preserveTurnOverlay = true)
        assertNull(BydNavigationOutputs.carPlayCall())
        assertNull(song.current())
        assertEquals(150, overlays.last()!!.distanceMeters)
        BydNavigationOutputs.endNow()
        assertNull(overlays.last())
        assertNoHardware()
    }

    private fun assertNoHardware() {
        assertEquals(0, Adb.connections)
        assertTrue(Shell.commands.toString(), Shell.commands.isEmpty())
        assertTrue(shadowOf(app).broadcastIntents.none { it.action == "byd.hud.NAVIGATION" })
    }

    @Implements(LocalAdb::class, isInAndroidSdk = false)
    class Adb {
        @Implementation fun connect(mayAsk: Boolean): LocalAdb.Access {
            connections++
            return LocalAdb.Access.READY
        }
        @Implementation fun shell(command: String): String? { Shell.commands += command; return null }
        @Implementation fun shell(command: String, timeout: Int): String? { Shell.commands += command; return null }
        companion object { var connections = 0 }
    }

    @Implements(BydAdbShell::class, isInAndroidSdk = false)
    class Shell {
        @Implementation fun run(context: Context, command: String): String? { commands += command; return null }
        companion object { val commands = CopyOnWriteArrayList<String>() }
    }
}
