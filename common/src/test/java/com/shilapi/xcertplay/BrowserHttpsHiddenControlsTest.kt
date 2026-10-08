package com.shilapi.xcertplay

import android.app.Activity
import android.os.Looper
import com.shilapi.xcertplay.browser.BrowserOutput
import com.shilapi.xcertplay.browser.BrowserTlsIdentity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** Home keeps lifecycle/automatic startup even though its settings views were never inflated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserHttpsHiddenControlsTest {
    private val activityController = Robolectric.buildActivity(Activity::class.java).setup()
    private val activity = activityController.get()
    private val controls = BrowserHttpsControls(activity)

    @Before fun prepare() {
        BrowserOutput.stop()
        BrowserHttpsVpnService.cancelStart()
        BrowserHttpsPreferences(activity).reset()
        BrowserHttpsForeground.enter(activity)
        // No real certificate, Keystore, asset, socket or VPN service is created by this fixture.
        ReflectionHelpers.setField(controls, "loaded", true)
        ReflectionHelpers.setField(controls, "loadedRevision", BrowserTlsStore(activity).revision())
        ReflectionHelpers.setField(controls, "identity", mock(BrowserTlsIdentity::class.java))
    }

    @After fun cleanup() {
        controls.onPause()
        controls.onStop()
        controls.onDestroy()
        BrowserHttpsForeground.leave(activity)
        BrowserHttpsPreferences(activity).reset()
        BrowserHttpsVpnService.cancelStart()
        activityController.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun repeatedHomeLifecycleDoesNotNeedSettingsViewsOrStartWithoutOptIn() {
        repeat(3) {
            controls.onResume(allowAutoStart = true)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(BrowserHttpsVpnService.hasPendingStart())
            assertNull(ReflectionHelpers.getField<Any?>(controls, "state"))
            assertNull(ReflectionHelpers.getField<Any?>(controls, "parked"))
            controls.onPause()
            controls.onStop()
        }
        // First settings visit after those home cycles can safely create and refresh every control.
        assertNotNull(controls.createView())
        controls.onResume()
        assertNotNull(ReflectionHelpers.getField<Any?>(controls, "state"))
    }

    @Test fun savedAutoStartCanPrepareConsentWithoutInflatingParkedCheckbox() {
        BrowserHttpsPreferences(activity).save(BrowserHttpsPreferences.Settings(autoStartOnOpen = true))
        controls.onResume(allowAutoStart = true)
        assertTrue(ReflectionHelpers.getField<Boolean>(controls, "parkedConsent"))
        assertNull(ReflectionHelpers.getField<Any?>(controls, "parked"))
        // Whether Android needs consent or immediately accepts the start, a second visit cannot
        // claim another automatic attempt or dereference a missing settings view.
        assertFalse(BrowserHttpsForeground.claimAutoStart())
        controls.onPause()
        controls.onStop()
        controls.onResume(allowAutoStart = true)
        assertNull(ReflectionHelpers.getField<Any?>(controls, "parked"))
    }
}
