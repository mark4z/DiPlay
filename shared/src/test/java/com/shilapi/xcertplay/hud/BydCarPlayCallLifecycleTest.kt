package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [BydCarPlayCallLifecycleTest.Shell::class])
class BydCarPlayCallLifecycleTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val writer get() = ReflectionHelpers.getField<ExecutorService>(BydCarPlayCall, "writer")
    private fun drain() { writer.submit {}.get(5, TimeUnit.SECONDS) }
    private fun call(id: String, status: Int = 2, name: String = "Caller") =
        Iap2Messages.buildRaw(CarPlayCallState.CALL_STATE_UPDATE) {
            string(1, name); u8(2, status); string(4, id)
        }

    @Before fun setup() {
        drain()
        ReflectionHelpers.getField<ScheduledFuture<*>?>(BydCarPlayCall, "retry")?.cancel(false)
        ReflectionHelpers.getField<CarPlayCallState>(BydCarPlayCall, "state").clear()
        listOf("shown", "watcherToken", "retry", "retryCard").forEach {
            ReflectionHelpers.setField(BydCarPlayCall, it, null)
        }
        listOf("watcherRunning", "cleanupPending", "prepared", "retryUsed").forEach {
            ReflectionHelpers.setField(BydCarPlayCall, it, false)
        }
        BydCarPlayCall.attach(app)
        BydOutputSettings.setCarPlayCalls(app, false)
        Shell.commands.clear()
    }

    @After fun cleanup() {
        BydOutputSettings.setCarPlayCalls(app, false)
        BydCarPlayCall.end()
        drain()
        ReflectionHelpers.getField<ScheduledFuture<*>?>(BydCarPlayCall, "retry")?.cancel(false)
        ReflectionHelpers.setField(BydCarPlayCall, "context", null)
    }

    @Test fun defaultOffTracksCallWithoutAnyShellOrWatcherMutation() {
        BydCarPlayCall.onFrame(call("a"))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "Caller"), BydCarPlayCall.current())
        assertHardwareIdle()
    }

    @Test fun savedEnabledPreferenceCannotStartCallOutputWatcherOrRetry() {
        BydOutputSettings.setCarPlayCalls(app, true)
        assertTrue(app.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE)
            .getBoolean("carplay_calls", false))
        BydCarPlayCall.onFrame(call("saved"))
        BydCarPlayCall.settingChanged(true)
        assertEquals(CarPlayCallCard.Phase.RINGING, BydCarPlayCall.current()?.phase)
        assertHardwareIdle()
    }

    @Test fun incomingActiveHeldAndDisconnectedFramesStillTrackGenericCallState() {
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.onFrame(call("lifecycle"))
        assertEquals(CarPlayCallCard.Phase.RINGING, BydCarPlayCall.current()?.phase)
        assertHardwareIdle()
        BydCarPlayCall.onFrame(call("lifecycle", status = 4))
        val active = BydCarPlayCall.current()!!
        assertEquals(CarPlayCallCard.Phase.ACTIVE, active.phase)
        assertNotNull(active.activeSinceMillis)
        assertHardwareIdle()
        BydCarPlayCall.onFrame(call("lifecycle", status = 5))
        assertEquals(active, BydCarPlayCall.current())
        BydCarPlayCall.onFrame(call("lifecycle", status = 0))
        assertNull(BydCarPlayCall.current())
        assertHardwareIdle()
    }

    @Test fun repeatedSettingChangesPreserveTheCallWithoutActivatingHardware() {
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.onFrame(call("unchanged"))
        val current = BydCarPlayCall.current()
        repeat(2) {
            BydOutputSettings.setCarPlayCalls(app, false)
            BydCarPlayCall.settingChanged(false)
            BydOutputSettings.setCarPlayCalls(app, true)
            BydCarPlayCall.settingChanged(true)
            BydCarPlayCall.onFrame(call("unchanged"))
            assertEquals(current, BydCarPlayCall.current())
            assertHardwareIdle()
        }
    }

    @Test fun queuedSettingAndSessionChangesCannotProduceLateHardwareWrites() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        writer.execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        try {
            BydOutputSettings.setCarPlayCalls(app, true)
            BydCarPlayCall.onFrame(call("old"))
            BydCarPlayCall.settingChanged(true)
            BydCarPlayCall.end()
            BydCarPlayCall.onFrame(call("new", status = 1, name = "New caller"))
            BydCarPlayCall.settingChanged(true)
        } finally {
            release.countDown()
        }
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.DIALING, "New caller"), BydCarPlayCall.current())
        assertHardwareIdle()
    }

    @Test fun sessionEndClearsCallStateAndNextSessionNeverAcquiresHardwareOwnership() {
        BydOutputSettings.setCarPlayCalls(app, true)
        BydCarPlayCall.onFrame(call("old", status = 4, name = "Old caller"))
        assertHardwareIdle()
        BydCarPlayCall.end()
        assertNull(BydCarPlayCall.current())
        assertHardwareIdle()
        BydCarPlayCall.onFrame(call("new", name = "New caller"))
        assertEquals(CarPlayCallCard(CarPlayCallCard.Phase.RINGING, "New caller"), BydCarPlayCall.current())
        assertHardwareIdle()
    }

    private fun assertHardwareIdle() {
        drain()
        assertFalse(BydHardwareIntegration.ENABLED)
        assertTrue("No BYD shell command may run", Shell.commands.isEmpty())
        assertNull(ReflectionHelpers.getField<Any?>(BydCarPlayCall, "shown"))
        assertNull(ReflectionHelpers.getField<String?>(BydCarPlayCall, "watcherToken"))
        assertFalse(ReflectionHelpers.getField<Boolean>(BydCarPlayCall, "watcherRunning"))
        assertFalse(ReflectionHelpers.getField<Boolean>(BydCarPlayCall, "cleanupPending"))
        assertFalse(ReflectionHelpers.getField<Boolean>(BydCarPlayCall, "prepared"))
        assertNull(ReflectionHelpers.getField<ScheduledFuture<*>?>(BydCarPlayCall, "retry"))
        assertFalse(ReflectionHelpers.getField<Boolean>(BydCarPlayCall, "retryUsed"))
    }

    @Implements(BydAdbShell::class, isInAndroidSdk = false)
    class Shell {
        @Implementation fun run(context: Context, command: String): String? {
            commands.add(command)
            return null
        }
        companion object {
            val commands = CopyOnWriteArrayList<String>()
        }
    }
}
