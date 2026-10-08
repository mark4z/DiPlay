package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import com.shilapi.xcertplay.browser.BrowserApprovalRequest
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
class BrowserApprovalUiTest {
    private lateinit var ui: BrowserApprovalUi
    private lateinit var activity: Activity

    @Before fun setUp() {
        BrowserOutput.stop()
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        BrowserHttpsPreferences(activity).reset()
        BrowserHttpsForeground.enter(activity)
        ui = BrowserApprovalUi(activity)
        ui.resume()
    }

    @After fun cleanUp() {
        ui.pause()
        BrowserHttpsPreferences(activity).reset()
        BrowserHttpsForeground.leave(activity)
        serviceFlag("ready", false)
        serviceFlag("running", false)
        BrowserOutput.stop()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun allowIsNotRejectedByThePositiveButtonsAutomaticDismiss() {
        val request = dispatch()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertTrue(dialog.isShowing)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(1, decision(request))
        assertFalse("One connection approval cannot be reused", request.approve())
    }

    @Test fun rejectAndBackCancelNeverApprove() {
        val request = dispatch()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        dialog.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, decision(request))
        assertFalse(request.approve())
    }

    @Test fun pauseDismissesPendingDialogAndRejectsTheConnection() {
        val request = dispatch()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        ui.pause()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertEquals(2, decision(request))
        assertFalse(BrowserOutput.isApprovalPending(request))
    }

    @Test fun aPostedRequestCannotShowAfterTheActivityPauses() {
        val request = dispatch()
        ui.pause()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(ShadowAlertDialog.getLatestAlertDialog()?.isShowing != true)
        assertEquals(2, decision(request))
    }

    @Test fun serverTimeoutOrCancellationDismissesTheDialog() {
        val request = dispatch()
        shadowOf(Looper.getMainLooper()).idle()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        BrowserOutput::class.java.getDeclaredMethod("finishApproval", java.lang.Long.TYPE, java.lang.Long.TYPE)
            .apply { isAccessible = true }.invoke(BrowserOutput, request.id, generation())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(dialog.isShowing)
        assertFalse(BrowserOutput.isApprovalPending(request))
        assertFalse(request.approve())
    }

    @Test fun automaticHttpsOptInApprovesWithoutDialogGestureOrTouchOwnership() {
        BrowserHttpsPreferences(activity).save(BrowserHttpsPreferences.Settings(autoAllowConnections = true))
        BrowserOutput::class.java.getDeclaredField("endpoint").apply { isAccessible = true }
            .set(null, "wss://tesla.mark4z.asia:9999/carplay")
        serviceFlag("ready", true); serviceFlag("running", true)
        val request = dispatch()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, decision(request))
        assertTrue(ShadowAlertDialog.getLatestAlertDialog()?.isShowing != true)
        assertFalse(BrowserOutput.browserTouchOwned)
        assertFalse(BrowserOutput.viewerConnected)
        val notice = org.robolectric.shadows.ShadowToast.getTextOfLatestToast().toString()
        assertTrue(notice.contains("automatically", ignoreCase = true) || notice.contains("自动"))
    }
    @Test fun savedAutomaticOptionDoesNotApplyToPlainHttpOrUnreadyHttps() {
        BrowserHttpsPreferences(activity).save(BrowserHttpsPreferences.Settings(autoAllowConnections = true))
        val request = dispatch()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, decision(request))
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing)
    }
    private fun serviceFlag(name: String, value: Boolean) {
        BrowserHttpsVpnService::class.java.getDeclaredField(name).apply { isAccessible = true }.setBoolean(null, value)
    }

    private fun dispatch(): BrowserApprovalRequest {
        val request = BrowserApprovalRequest::class.java.getDeclaredConstructor(java.lang.Long.TYPE,
            String::class.java, java.lang.Long.TYPE, kotlin.jvm.functions.Function0::class.java)
            .apply { isAccessible = true }
            .newInstance(1L, "192.168.40.5", System.nanoTime() / 1_000_000L + 30_000L, { true })
        BrowserOutput::class.java.getDeclaredMethod("requestApproval", BrowserApprovalRequest::class.java,
            java.lang.Long.TYPE).apply { isAccessible = true }.invoke(BrowserOutput, request, generation())
        return request
    }

    private fun generation() = BrowserOutput::class.java.getDeclaredField("serverGeneration")
        .apply { isAccessible = true }.getLong(null)

    private fun decision(request: BrowserApprovalRequest) = BrowserApprovalRequest::class.java
        .getDeclaredField("decision").apply { isAccessible = true }.getInt(request)
}

