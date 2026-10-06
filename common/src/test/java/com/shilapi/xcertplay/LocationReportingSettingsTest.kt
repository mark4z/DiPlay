package com.shilapi.xcertplay

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class LocationReportingSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<DiPlayActivity>? = null
    private val activity get() = requireNotNull(controller).get()

    @Before fun setUp() {
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @After fun tearDown() {
        CarPlayBackgroundSession.clear()
        controller?.pause()?.stop()?.destroy()
    }

    @Test fun currentSettingsExposeLocationSwitchWithDefaultOff() {
        openSettings()

        assertFalse(locationSwitch().isChecked)
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun originalSettingsKeepGpsAudioMapsAndDiagnosticsWhileVehicleControlsAreHidden() {
        com.shilapi.xcertplay.hud.BydOutputSettings.setBatteryToIphone(context, true)
        com.shilapi.xcertplay.hud.BydOutputSettings.setWheelSpeedToIphone(context, true)
        com.shilapi.xcertplay.hud.BydOutputSettings.setVideoWhileParked(context, true)
        AirPlayPersistence.saveAdbClusterEnabled(context, true)
        openSettings()
        val labels = descendants(activity.window.decorView).filterIsInstance<TextView>()
            .map { it.text.toString() }.toList()
        for (resource in listOf(R.string.audio_routing, R.string.car_button_in_carplay,
            R.string.carplay_map_on_instrument_cluster_experimental, R.string.diagnostics,
            R.string.save_diagnostic_report, R.string.byd_hardware_disabled)) {
            assertTrue(activity.getString(resource), labels.contains(activity.getString(resource)))
        }
        assertNotNull(locationSwitch())
        assertFalse(labels.contains(activity.getString(R.string.advanced_vehicle_data)))
        assertFalse(labels.contains(activity.getString(R.string.adb_cluster_activity_mode)))
        assertFalse(labels.contains(activity.getString(R.string.video_while_parked)))
    }

    @Test fun originalAudioSettingsKeepStandardCallKeysOptInWithoutVendorControls() {
        com.shilapi.xcertplay.hud.BydOutputSettings.setCarPlayCallControls(context, false)
        openSettings()
        fun callSwitch(): Switch = descendants(activity.window.decorView).filterIsInstance<Switch>()
            .single { it.contentDescription == activity.getString(R.string.standard_call_keys) }
        assertFalse(callSwitch().isChecked)
        callSwitch().performClick()
        assertTrue(com.shilapi.xcertplay.hud.BydOutputSettings.carPlayCallControls(context))
        requireNotNull(controller).recreate()
        assertTrue(callSwitch().isChecked)
        assertFalse(descendants(activity.window.decorView).filterIsInstance<TextView>()
            .any { it.text == activity.getString(R.string.byd_navigation) })
        callSwitch().performClick()
        assertFalse(com.shilapi.xcertplay.hud.BydOutputSettings.carPlayCallControls(context))
    }

    @Test fun grantedSettingSurvivesRecreationAndCanBeDisabled() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        openSettings()

        locationSwitch().performClick()
        assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
        requireNotNull(controller).recreate()
        assertTrue(locationSwitch().isChecked)

        locationSwitch().performClick()
        assertFalse(locationSwitch().isChecked)
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun enablingWaitsForPrecisePermissionAndRejectsApproximateOnly() {
        openSettings()
        locationSwitch().performClick()

        assertFalse(locationSwitch().isChecked)
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
        val request = shadowOf(activity).lastRequestedPermission
        assertArrayEquals(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            request.requestedPermissions)

        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_DENIED, PackageManager.PERMISSION_GRANTED))
        assertFalse(locationSwitch().isChecked)
        assertFalse(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun precisePermissionResultEnablesReporting() {
        openSettings()
        locationSwitch().performClick()
        val request = shadowOf(activity).lastRequestedPermission

        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
            intArrayOf(PackageManager.PERMISSION_GRANTED, PackageManager.PERMISSION_GRANTED))

        assertTrue(locationSwitch().isChecked)
        assertTrue(AirPlayPersistence.loadLocationReportingEnabled(context))
    }

    @Test fun eitherToggleDirectionStopsTheOldSessionAndOpensANewHostWithSavedSetting() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        AirPlayPersistence.saveWirelessEnabled(context, false)
        openSettings()
        // Authentication assets are deliberately absent from unit tests. Model an already
        // provisioned, connected host without constructing transports or native decoders.
        ReflectionHelpers.setField(activity, "setupError", null)
        val stoppedWithSettings = mutableListOf<Boolean>()

        for (enabled in listOf(true, false)) {
            val stop: ((() -> Unit) -> Unit) = { completion ->
                stoppedWithSettings += AirPlayPersistence.loadLocationReportingEnabled(context)
                CarPlayBackgroundSession.clear()
                completion()
            }
            ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)

            locationSwitch().performClick()

            assertEquals(enabled, AirPlayPersistence.loadLocationReportingEnabled(context))
            assertEquals(CarPlayHostActivity::class.java.name, shadowOf(activity).nextStartedActivity.component?.className)
        }
        assertEquals(listOf(true, false), stoppedWithSettings)
    }

    private fun openSettings() {
        controller = Robolectric.buildActivity(DiPlayActivity::class.java,
            Intent(context, DiPlayActivity::class.java).putExtra("page", "settings")).setup()
    }

    private fun locationSwitch(): Switch = descendants(activity.window.decorView)
        .filterIsInstance<Switch>()
        .single { it.contentDescription == activity.getString(R.string.report_location_to_iphone) }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
