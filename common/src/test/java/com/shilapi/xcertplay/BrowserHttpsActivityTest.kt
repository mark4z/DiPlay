package com.shilapi.xcertplay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.Switch
import org.robolectric.shadows.ShadowAlertDialog
import com.shilapi.xcertplay.browser.BrowserOutput
import com.shilapi.xcertplay.browser.BrowserSession
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
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/** No real identity, provider, VPN consent, socket or private archive is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserHttpsActivityTest {
    private lateinit var controller: org.robolectric.android.controller.ActivityController<BrowserHttpsActivity>
    private val activity get() = controller.get()

    @Before fun setUp() {
        BrowserOutput.stop()
        BrowserHttpsVpnService.cancelStart()
        controller = Robolectric.buildActivity(BrowserHttpsActivity::class.java).setup()
        field("loadGeneration", 100)
        field("loading", false)
        field("loaded", true)
        field("loadedRevision", BrowserTlsStore(activity).revision())
        invoke("refreshControls")
    }
    @After fun cleanUp() {
        serviceFlag("running", false); serviceFlag("ready", false)
        controller.pause().stop().destroy()
        BrowserHttpsVpnService.cancelStart()
        BrowserOutput.stop()
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun openingNeverStartsAndRequiresBothImportAndParkedAcknowledgement() {
        assertFalse(BrowserHttpsVpnService.running)
        assertFalse(BrowserHttpsVpnService.hasPendingStart())
        assertFalse(button("Start HTTPS").isEnabled)
        field("identity", mock(BrowserTlsIdentity::class.java))
        invoke("refreshControls")
        assertFalse(button("Start HTTPS").isEnabled)
        find(activity.window.decorView) { it is CheckBox }.let { (it as CheckBox).isChecked = true }
        assertTrue(button("Start HTTPS").isEnabled)
    }
    @Test fun pickerIsFreshLocalReadOnlyAndCancelledSelectionDoesNotStart() {
        button("Import local").performClick()
        val picker = shadowOf(activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
        assertTrue(picker.hasCategory(Intent.CATEGORY_OPENABLE))
        assertTrue(picker.getBooleanExtra(Intent.EXTRA_LOCAL_ONLY, false))
        assertEquals(0, picker.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, picker.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        result(9911, Activity.RESULT_CANCELED, null)
        assertEquals(false, field("selecting"))
        assertNull(field("identity"))
        assertFalse(BrowserHttpsVpnService.hasPendingStart())
    }
    @Test fun cancelledOrNonContentResultPreservesExistingIdentityAndNeverReads() {
        val identity = mock(BrowserTlsIdentity::class.java)
        field("identity", identity)
        button("Import local").performClick()
        result(9911, Activity.RESULT_OK, Intent().setData(Uri.parse("file:///synthetic-not-opened.zip")))
        assertSame(identity, field("identity"))
        assertNull(field("importTask"))
        assertTrue(field("importStatus").toString().contains("CONTENT_URI_REQUIRED"))
    }
    @Test fun stopKeepsSavedIdentityAndBackgroundCancelsOutstandingImport() {
        val closed = AtomicInteger()
        val task = BrowserSession({ it.run() }, {})
        task.own(Closeable { closed.incrementAndGet() })
        field("importTask", task)
        field("identity", mock(BrowserTlsIdentity::class.java))
        field("candidate", mock(BrowserTlsIdentity::class.java))
        invoke("stop")
        assertNotNull(field("identity"))
        controller.pause().stop()
        assertTrue(task.isCancelled)
        assertEquals(1, closed.get())
        assertNotNull(field("identity")); assertNull(field("candidate"))
        task.workerFinished()
        assertFalse(BrowserHttpsVpnService.hasPendingStart())
        controller.restart().start().resume()
    }
    @Test fun recreationDropsIdentityPendingConsentAndLatePickerResults() {
        field("identity", mock(BrowserTlsIdentity::class.java))
        field("systemPrompt", true)
        field("pending", true)
        field("selecting", true)
        BrowserHttpsVpnService.armStart(mock(BrowserTlsIdentity::class.java), BrowserTlsStore(activity).revision())
        val saved = Bundle()
        controller.saveInstanceState(saved).pause().stop().destroy()
        assertFalse(BrowserHttpsVpnService.hasPendingStart())
        controller = Robolectric.buildActivity(BrowserHttpsActivity::class.java).create(saved).start().resume().visible()
        assertNull(field("identity")); assertEquals(false, field("pending")); assertEquals(false, field("selecting"))
        result(9911, Activity.RESULT_OK, Intent().setData(Uri.parse("content://synthetic/ignored")))
        result(9912, Activity.RESULT_OK, null)
        assertNull(field("importTask"))
        assertFalse(BrowserHttpsVpnService.hasPendingStart())
    }
    @Test fun automaticOptionsAreOffUntilTheDisclosureIsAccepted() {
        val start = find(activity.window.decorView) { it is Switch && it.text.toString().startsWith("Start HTTPS when") } as Switch
        val allow = find(activity.window.decorView) { it is Switch && it.text.toString().startsWith("Automatically allow") } as Switch
        assertFalse(start.isChecked); assertFalse(allow.isChecked)
        allow.performClick()
        assertFalse(allow.isChecked)
        ShadowAlertDialog.getLatestAlertDialog().cancel()
        assertFalse(BrowserHttpsPreferences(activity).load().autoAllowConnections)
    }
    @Test fun vpnDenialRetainsIdentityAndCannotLoopOnResume() {
        val identity = mock(BrowserTlsIdentity::class.java)
        field("identity", identity)
        field("pending", true); field("systemPrompt", true)
        BrowserHttpsForeground.suppressAutoStart()
        result(9912, Activity.RESULT_CANCELED, null)
        assertEquals(false, field("pending"))
        assertSame(identity, field("identity"))
        assertFalse(BrowserHttpsForeground.claimAutoStart())
        assertFalse(BrowserHttpsVpnService.hasPendingStart())
    }
    @Test fun deleteImmediatelyDropsMemoryAndCancelsImportBeforeAsyncErasure() {
        field("identity", mock(BrowserTlsIdentity::class.java))
        field("candidate", mock(BrowserTlsIdentity::class.java))
        activity.controls.deleteSaved()
        assertNull(field("identity")); assertNull(field("candidate"))
        assertFalse(BrowserHttpsVpnService.hasPendingStart())
    }
    @Test fun carPlayContinuationWaitsForHttpsReadinessAndRunsOnlyOnce() {
        field("settings", BrowserHttpsPreferences.Settings(autoStartOnOpen = true))
        field("autoStartEligible", true)
        serviceFlag("running", true); serviceFlag("ready", false)
        var continued = 0
        activity.controls.afterInitialAutoStart { continued++ }
        invoke("finishAutomaticStart")
        assertEquals(0, continued)
        serviceFlag("ready", true)
        invoke("finishAutomaticStart"); invoke("finishAutomaticStart")
        assertEquals(1, continued)
    }
    @Test fun anotherScreenDeletingIdentityInvalidatesThisScreensMemoryBeforeStarting() {
        field("identity", mock(BrowserTlsIdentity::class.java))
        runCatching { BrowserTlsStore(activity).delete() } // no real Keystore alias exists in this fixture
        activity.controls.onResume()
        assertNull(field("identity"))
        assertFalse(BrowserHttpsVpnService.hasPendingStart())
    }
    private fun serviceFlag(name: String, value: Boolean) {
        BrowserHttpsVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.setBoolean(null, value)
    }

    private fun button(prefix: String) = find(activity.window.decorView) {
        it is Button && it.text.toString().startsWith(prefix)
    } as Button
    private fun find(view: View, matches: (View) -> Boolean): View {
        if (matches(view)) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            try { return find(view.getChildAt(i), matches) } catch (_: NoSuchElementException) { }
        }
        throw NoSuchElementException()
    }
    private fun field(name: String): Any? = BrowserHttpsControls::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity.controls)
    private fun field(name: String, value: Any?) { BrowserHttpsControls::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity.controls, value) }
    private fun invoke(name: String) { BrowserHttpsControls::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity.controls) }
    private fun result(request: Int, result: Int, data: Intent?) {
        BrowserHttpsActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }.invoke(activity, request, result, data)
    }
}
