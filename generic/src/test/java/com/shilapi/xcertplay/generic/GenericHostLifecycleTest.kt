package com.shilapi.xcertplay.generic

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GenericHostLifecycleTest {
    @Test fun launchingDoesNotStartAConnectionOrRequestPermissions() {
        val lifecycle = Robolectric.buildActivity(GenericCarPlayActivity::class.java).create().start().resume()
        val activity = lifecycle.get()
        assertFalse(field(activity, "desired"))
        assertFalse(field(activity, "awaitingPermission"))
        assertFalse(field(activity, "awaitingVpn"))
        assertNull(field<Any?>(activity, "controller"))
        assertNull(field<Any?>(activity, "sink"))
        lifecycle.pause().stop().destroy()
    }

    @Test fun recreateWhilePermissionPromptIsPendingWaitsForItsResult() {
        val saved = Bundle().apply {
            putBoolean("user_started", true)
            putBoolean("awaiting_permission", true)
            putBoolean("microphone_prompted", true)
        }
        val lifecycle = Robolectric.buildActivity(GenericCarPlayActivity::class.java).create(saved).start().resume()
        val activity = lifecycle.get()
        assertTrue(field(activity, "desired"))
        assertTrue(field(activity, "awaitingPermission"))
        assertFalse(field(activity, "preparing"))
        assertNull(field<Any?>(activity, "controller"))
        val roundTrip = Bundle()
        lifecycle.saveInstanceState(roundTrip)
        assertTrue(roundTrip.getBoolean("awaiting_permission"))
        assertTrue(roundTrip.getBoolean("microphone_prompted"))
        lifecycle.pause().stop().destroy()
    }

    @Test fun recreateWhileVpnPromptIsPendingDoesNotReplaceIt() {
        val saved = Bundle().apply { putBoolean("user_started", true); putBoolean("awaiting_vpn", true) }
        val lifecycle = Robolectric.buildActivity(GenericCarPlayActivity::class.java).create(saved).start().resume()
        val activity = lifecycle.get()
        assertTrue(field(activity, "desired"))
        assertTrue(field(activity, "awaitingVpn"))
        assertFalse(field(activity, "preparing"))
        val roundTrip = Bundle()
        lifecycle.saveInstanceState(roundTrip)
        assertTrue(roundTrip.getBoolean("awaiting_vpn"))
        lifecycle.pause().stop().destroy()
    }

    @Test fun repeatedMenuDoesNotStackSettingsAndCancelPreservesSettings() {
        val lifecycle = Robolectric.buildActivity(GenericCarPlayActivity::class.java).create().start().resume()
        val activity = lifecycle.get()
        val original = field<GenericSettings>(activity, "settings")
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU))
        val dialog = field<AlertDialog>(activity, "settingsDialog")
        activity.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MENU))
        assertSame(dialog, field<AlertDialog>(activity, "settingsDialog"))
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(original, field<GenericSettings>(activity, "settings"))
        assertNull(field<AlertDialog?>(activity, "settingsDialog"))
        lifecycle.pause().stop().destroy()
    }

    @Test @Config(sdk = [33]) fun deniedRequiredPermissionStopsPendingStart() {
        val lifecycle = Robolectric.buildActivity(GenericCarPlayActivity::class.java).create().start().resume()
        val activity = lifecycle.get()
        ReflectionHelpers.setField(activity, "settings", GenericSettings(wireless = true))
        ReflectionHelpers.setField(activity, "desired", true)
        ReflectionHelpers.setField(activity, "awaitingPermission", true)
        activity.onRequestPermissionsResult(100, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), intArrayOf(PackageManager.PERMISSION_DENIED))
        assertFalse(field(activity, "desired"))
        assertFalse(field(activity, "awaitingPermission"))
        assertFalse(field(activity, "retryPending"))
        assertNull(field<Any?>(activity, "controller"))
        lifecycle.pause().stop().destroy()
    }

    private fun <T> field(activity: GenericCarPlayActivity, name: String): T = ReflectionHelpers.getField(activity, name)
}
