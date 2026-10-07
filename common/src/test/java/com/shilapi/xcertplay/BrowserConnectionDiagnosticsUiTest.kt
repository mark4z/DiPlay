package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.shilapi.xcertplay.browser.BrowserOutput
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserConnectionDiagnosticsUiTest {
    private lateinit var activity: Activity
    private lateinit var controller: org.robolectric.android.controller.ActivityController<Activity>

    @Before fun setUp() {
        BrowserOutput.stop()
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        activity = controller.get()
        // This fixture supplies only a synthetic endpoint: no socket or network permission is used.
        BrowserOutput::class.java.getDeclaredField("endpoint").apply { isAccessible = true }
            .set(null, "ws://192.168.40.2:41234/carplay")
    }

    @After fun cleanUp() {
        ShadowAlertDialog.getLatestAlertDialog()?.dismiss()
        BrowserOutput.stop()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun healthUrlIsPlainHttpWithNoCredentialsOrQueryAndClearsOnStop() {
        assertEquals("http://192.168.40.2:41234/health", BrowserOutput.healthEndpoint)
        BrowserOutput.stop()
        assertNull(BrowserOutput.healthEndpoint)
        assertTrue(BrowserOutput.connectionDiagnosticReport().contains("listener OFF"))
    }

    @Test fun healthAddressCanBeCopiedWithoutOpeningBrowserOrRequestingConsent() {
        BrowserOutputSettings.create(activity).performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        button(dialog.window!!.decorView, "Copy health URL").performClick()
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("http://192.168.40.2:41234/health", clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(BrowserOutput.viewerConnected)
    }

    @Test fun copyReportKeepsTheDiagnosticsDialogOpenAndCloseCancelsRefresh() {
        BrowserOutputSettings.create(activity).performClick()
        button(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView, "Connection diagnostics").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        // OnShow is posted to the paused main looper; let it install the copy listener.
        shadowOf(Looper.getMainLooper()).idle()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
        assertTrue(dialog.isShowing)
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val report = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertTrue(report.contains("listener OFF"))
        assertFalse(report.contains("192.168"))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertFalse(dialog.isShowing)
        assertFalse(BrowserOutput.viewerConnected)
    }

    @Test fun destroyingTheHostStopsTheDiagnosticRefresh() {
        BrowserOutputSettings.create(activity).performClick()
        button(ShadowAlertDialog.getLatestAlertDialog().window!!.decorView, "Connection diagnostics").performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2))
        assertFalse(dialog.isShowing)
    }

    private fun button(view: View, prefix: String): Button {
        if (view is Button && view.text.toString().startsWith(prefix)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                try { return button(view.getChildAt(index), prefix) } catch (_: NoSuchElementException) { }
            }
        }
        throw NoSuchElementException(prefix)
    }
}
