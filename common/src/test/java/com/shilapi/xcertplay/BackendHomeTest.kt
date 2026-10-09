package com.shilapi.xcertplay

import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

/** No live controller, accessory identity, VPN, or socket: tests the home/session handoff. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w1000dp-h700dp")
@LooperMode(LooperMode.Mode.PAUSED)
class BackendHomeTest {
    private lateinit var lifecycle: ActivityController<DiPlayActivity>
    private val activity get() = lifecycle.get()
    private val owner = Any()

    @Before fun create() {
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopping", false)
        ReflectionHelpers.setField(DiPlayBootstrap, "ready", false)
        lifecycle = Robolectric.buildActivity(DiPlayActivity::class.java).setup()
        // Authentication is tested independently. This fixture never starts a connection.
        ReflectionHelpers.setField(activity, "setupError", null)
    }

    @After fun destroy() {
        CarPlayBackgroundSession.clear()
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopping", false)
        BrowserHttpsPreferences(activity).reset()
        lifecycle.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun running(backend: Boolean) {
        BrowserHttpsPreferences(activity).save(BrowserHttpsPreferences.Settings(relayOnly = backend))
        ReflectionHelpers.setField(CarPlayBackgroundSession, "owner", owner)
        val stop: (() -> Unit) -> Unit = { completion -> CarPlayBackgroundSession.clear(); completion() }
        ReflectionHelpers.setField(CarPlayBackgroundSession, "stopAction", stop)
        refresh()
    }

    private fun refresh() = DiPlayActivity::class.java.getDeclaredMethod("refreshStatus")
        .apply { isAccessible = true }.invoke(activity)
    private fun connect() = ReflectionHelpers.getField<Button>(activity, "connectButton")
    private fun status() = ReflectionHelpers.getField<TextView>(activity, "status").text.toString()
    private fun disconnect() = ReflectionHelpers.getField<Button>(activity, "disconnectButton")

    @Test fun backendShowsProgressAndRetryInsteadOfOpenCarPlay() {
        running(backend = true)
        CarPlayBackgroundSession.updateConnectionStage(owner, "Waiting for paired iPhone")
        refresh()
        assertEquals("Waiting for paired iPhone", status())
        assertEquals(activity.getString(R.string.retry_carplay_connection), connect().text.toString())
        assertEquals(View.VISIBLE, disconnect().visibility)
        assertNull(shadowOf(activity).nextStartedActivity)
        CarPlayBackgroundSession.active = true
        refresh()
        assertEquals(activity.getString(R.string.carplay_connected), status())
    }

    @Config(qualifiers = "en-w500dp-h400dp")
    @Test fun compactBackendUsesTheSameProgressAndAction() {
        backendShowsProgressAndRetryInsteadOfOpenCarPlay()
    }

    @Test fun ordinarySessionStillOpensPlayback() {
        running(backend = false)
        assertEquals(activity.getString(R.string.open_carplay), connect().text.toString())
        connect().performClick()
        assertEquals(CarPlayHostActivity::class.java.name,
            shadowOf(activity).nextStartedActivity.component?.className)
    }

    @Test fun disconnectClearsProgressAndRestoresConnectAction() {
        running(backend = true)
        CarPlayBackgroundSession.updateConnectionStage(owner, "Old session progress")
        disconnect().performClick()
        refresh()
        assertNull(CarPlayBackgroundSession.connectionStage)
        assertEquals(activity.getString(R.string.connect_phone), connect().text.toString())
        assertEquals(View.GONE, disconnect().visibility)
    }

    @Test fun staleOwnerCannotOverwriteProgressOrRestoreItAfterStop() {
        running(backend = true)
        CarPlayBackgroundSession.updateConnectionStage(owner, "Current session")
        CarPlayBackgroundSession.updateConnectionStage(Any(), "Stale session")
        assertEquals("Current session", CarPlayBackgroundSession.connectionStage)
        CarPlayBackgroundSession.clear(keepOwner = true)
        CarPlayBackgroundSession.updateConnectionStage(owner, "Reconnecting")
        assertEquals("Reconnecting", CarPlayBackgroundSession.connectionStage)
        CarPlayBackgroundSession.clear()
        CarPlayBackgroundSession.updateConnectionStage(owner, "Late callback")
        assertNull(CarPlayBackgroundSession.connectionStage)
    }
}
