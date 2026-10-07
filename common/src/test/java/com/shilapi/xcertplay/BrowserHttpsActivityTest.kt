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
    }
    @After fun cleanUp() {
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
    @Test fun stopAndBackgroundCancelOutstandingImportAndForgetIdentity() {
        val closed = AtomicInteger()
        val task = BrowserSession({ it.run() }, {})
        task.own(Closeable { closed.incrementAndGet() })
        field("importTask", task)
        field("identity", mock(BrowserTlsIdentity::class.java))
        field("candidate", mock(BrowserTlsIdentity::class.java))
        button("Stop and forget").performClick()
        assertTrue(task.isCancelled)
        assertEquals(1, closed.get())
        assertNull(field("identity")); assertNull(field("candidate"))
        task.workerFinished()
        controller.pause().stop()
        assertFalse(BrowserHttpsVpnService.hasPendingStart())
        controller.restart().start().resume()
    }
    @Test fun recreationDropsIdentityPendingConsentAndLatePickerResults() {
        field("identity", mock(BrowserTlsIdentity::class.java))
        field("systemPrompt", true)
        field("pending", true)
        field("selecting", true)
        BrowserHttpsVpnService.armStart(mock(BrowserTlsIdentity::class.java))
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
    private fun field(name: String): Any? = BrowserHttpsActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun field(name: String, value: Any?) { BrowserHttpsActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(activity, value) }
    private fun invoke(name: String) { BrowserHttpsActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity) }
    private fun result(request: Int, result: Int, data: Intent?) {
        BrowserHttpsActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }.invoke(activity, request, result, data)
    }
}
