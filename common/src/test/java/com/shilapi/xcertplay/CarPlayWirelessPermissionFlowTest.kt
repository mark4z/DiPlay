package com.shilapi.xcertplay

import android.os.Build
import android.os.Handler
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Actual activity wiring on supported Robolectric SDKs; API-37 policy is tested separately. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], qualifiers = "en")
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayWirelessPermissionFlowTest {
    private lateinit var activity: CarPlayHostActivity
    private val launched = mutableListOf<List<String>>()

    @Before fun setUp() {
        CarPlayBackgroundSession.clear()
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        ReflectionHelpers.setField(activity, "wirelessEnabled", true)
        ReflectionHelpers.setField(activity, "wirelessHotspotMode", WirelessHotspotMode.MANUAL)
        ReflectionHelpers.setField(activity, "wirelessPermissions", object : ActivityResultLauncher<Array<String>>() {
            override fun launch(input: Array<String>, options: ActivityOptionsCompat?) {
                launched += input.toList()
            }
            override fun unregister() = Unit
            override fun getContract() = ActivityResultContracts.RequestMultiplePermissions()
        })
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(*required().toTypedArray())
    }

    @After fun tearDown() {
        ReflectionHelpers.getField<AtomicBoolean>(activity, "shuttingDown").set(true)
        ReflectionHelpers.getField<Handler>(activity, "mainHandler").removeCallbacksAndMessages(null)
        ReflectionHelpers.getField<ExecutorService>(activity, "teardownExecutor").shutdownNow()
        ReflectionHelpers.getField<ExecutorService>(activity, "airPlayCommandExecutor").shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun activityUsesTheSharedPolicyForEveryMode() {
        for (mode in WirelessHotspotMode.values()) {
            ReflectionHelpers.setField(activity, "wirelessHotspotMode", mode)
            assertEquals(WirelessPermissions.required(mode, Build.VERSION.SDK_INT), required())
        }
    }

    @Test fun missingPermissionRequestsThroughTheExistingLauncherWithoutAuthorizingStartup() {
        val expected = required()
        invoke<Unit>("requestWirelessPermissions")
        assertEquals(listOf(expected), launched)
        assertFalse(field("wirelessPermissionsReady"))
        assertTrue(field("awaitingWirelessPermissions"))
        assertFalse(invoke<Boolean>("hasRequiredWirelessPermissions"))
        assertNull(ReflectionHelpers.getField<Any?>(activity, "controller"))
        assertFalse(field("vpnReady"))
    }

    @Test fun alreadyGrantedPermissionsDoNotLaunchAnotherRequest() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(*required().toTypedArray())
        invoke<Unit>("requestWirelessPermissions")
        assertTrue(launched.isEmpty())
        assertTrue(field("wirelessPermissionsReady"))
        assertFalse(field("awaitingWirelessPermissions"))
        assertTrue(invoke<Boolean>("hasRequiredWirelessPermissions"))
    }

    @Test fun oneGrantedPermissionDoesNotSatisfyTheWholePolicy() {
        val expected = required()
        assertTrue(expected.size > 1)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(expected.first())
        invoke<Unit>("requestWirelessPermissions")
        assertEquals(listOf(expected), launched)
        assertFalse(field("wirelessPermissionsReady"))
        assertFalse(invoke<Boolean>("hasRequiredWirelessPermissions"))
    }

    private fun required(): List<String> = invoke("requiredWirelessPermissions")
    private fun field(name: String): Boolean = ReflectionHelpers.getField(activity, name)
    private fun <T> invoke(name: String): T = ReflectionHelpers.callInstanceMethod(activity, name)
}
