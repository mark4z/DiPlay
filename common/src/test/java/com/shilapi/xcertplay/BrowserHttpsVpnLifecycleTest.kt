package com.shilapi.xcertplay

import android.app.Service
import android.content.Intent
import android.os.Looper
import com.shilapi.xcertplay.browser.BrowserInterfaces
import com.shilapi.xcertplay.browser.BrowserSession
import com.shilapi.xcertplay.browser.BrowserTlsIdentity
import org.mockito.Mockito.mock
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
import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class BrowserHttpsVpnLifecycleTest {
    private lateinit var controller: org.robolectric.android.controller.ServiceController<BrowserHttpsVpnService>
    private val service get() = controller.get()
    @Before fun setUp() {
        BrowserHttpsVpnService.cancelStart()
        controller = Robolectric.buildService(BrowserHttpsVpnService::class.java).create()
    }
    @After fun cleanUp() { controller.destroy(); shadowOf(Looper.getMainLooper()).idle() }
    @Test fun nullOrUnauthenticatedStartsAreNeverStickyAndNeverCreateSession() {
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent().setAction(BrowserHttpsVpnService.START).putExtra("grant", 42L), 0, 2))
        assertNull(session())
        assertFalse(BrowserHttpsVpnService.running)
    }
    @Test fun changedOrDeletedSavedIdentityCannotStartFromAnOlderGrant() {
        val revision = BrowserTlsStore(service).revision()
        val token = BrowserHttpsVpnService.armStart(mock(BrowserTlsIdentity::class.java), revision - 1)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(
            Intent().setAction(BrowserHttpsVpnService.START).putExtra("grant", token), 0, 1))
        assertNull(session())
        assertFalse(BrowserHttpsVpnService.hasPendingStart())
        assertFalse(BrowserHttpsVpnService.running)
    }
    @Test fun startupFailureSurvivesCleanupCommandsAndAppearsInExistingReport() {
        attachSyntheticResources()
        BrowserHttpsVpnService::class.java.getDeclaredField("selfCheck").apply { isAccessible = true }
            .set(null, "NOT_RUN")
        BrowserHttpsVpnService::class.java.getDeclaredMethod("fail", BrowserSession::class.java,
            String::class.java, String::class.java).apply { isAccessible = true }
            .invoke(service, session(), "LISTENER_BIND_BIND_FAILED_ERRNO_98",
                "LISTENER_BIND_BIND_FAILED_ERRNO_98; causes=BindException -> ErrnoException")
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session()!!.isCancelled)
        service.onStartCommand(Intent().setAction(BrowserHttpsVpnService.STOP), 0, 1)
        service.onDestroy()
        val report = BrowserHttpsVpnService.diagnosticReport()
        assertTrue(report.contains("LISTENER_BIND_BIND_FAILED_ERRNO_98"))
        assertTrue(report.contains("causes=BindException -> ErrnoException"))
        assertEquals("NOT_RUN", BrowserHttpsVpnService.selfCheck)
    }

    @Test fun finalCleanupKeepsFailureVisibleAndOnlyBlocksRetryWhenUnconfirmed() {
        try {
            for (readFailed in listOf(false, true)) {
                attachSyntheticResources()
                val current = session()!!
                BrowserHttpsVpnService::class.java.getDeclaredMethod("fail", BrowserSession::class.java,
                    String::class.java, String::class.java).apply { isAccessible = true }
                    .invoke(service, current, "VPN_COMPATIBILITY_IO_FAILED", "VPN_COMPATIBILITY_IO_FAILED; causes=IOException")
                shadowOf(Looper.getMainLooper()).idle()
                current.workerFinished()
                val snapshot = BrowserInterfaces.Snapshot::class.java.getDeclaredConstructor(
                    String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }.newInstance("synthetic cleanup snapshot", false, readFailed)
                BrowserHttpsVpnService::class.java.getDeclaredMethod("finishStopped", BrowserSession::class.java,
                    BrowserInterfaces.Snapshot::class.java).apply { isAccessible = true }.invoke(service, current, snapshot)
                assertNull(session())
                assertFalse(BrowserHttpsVpnService.running)
                assertEquals(readFailed, BrowserHttpsVpnService.recoveryRequired)
                assertTrue(BrowserHttpsVpnService.status.contains("VPN_COMPATIBILITY_IO_FAILED"))
                assertTrue(BrowserHttpsVpnService.diagnosticReport().contains("causes=IOException"))
            }
        } finally {
            BrowserHttpsVpnService::class.java.getDeclaredField("recoveryRequired").apply { isAccessible = true }
                .setBoolean(null, false)
        }
    }

    @Test fun failureBeforeSelfCheckDoesNotClaimTheCheckWasCancelled() {
        attachSyntheticResources()
        BrowserHttpsVpnService::class.java.getDeclaredField("selfCheck").apply { isAccessible = true }
            .set(null, "NOT_RUN")
        service.onStartCommand(Intent().setAction(BrowserHttpsVpnService.STOP), 0, 1)
        assertEquals("NOT_RUN", BrowserHttpsVpnService.selfCheck)
    }

    @Test fun stopDuringActualSelfCheckStillReportsCancellation() {
        attachSyntheticResources()
        BrowserHttpsVpnService::class.java.getDeclaredField("selfCheck").apply { isAccessible = true }
            .set(null, "PENDING")
        service.onStartCommand(Intent().setAction(BrowserHttpsVpnService.STOP), 0, 1)
        assertEquals("CANCELLED", BrowserHttpsVpnService.selfCheck)
    }

    @Test fun explicitStopClosesBothDescriptorsAndSocketOnce() {
        val closes = attachSyntheticResources()
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent().setAction(BrowserHttpsVpnService.STOP), 0, 1))
        assertTrue(session()!!.isCancelled)
        assertEquals(3, closes.get())
        service.onStartCommand(Intent().setAction(BrowserHttpsVpnService.STOP), 0, 2)
        assertEquals(3, closes.get())
    }
    @Test fun revokeAndDestroyCloseEveryOwnedResource() {
        val closes = attachSyntheticResources()
        service.onRevoke()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(session()!!.isCancelled)
        assertEquals(3, closes.get())
        service.onDestroy()
        assertEquals(3, closes.get())
    }
    @Test fun taskRemovalAndLegacyBackgroundCommandsKeepExplicitServiceAlive() {
        val closes = attachSyntheticResources()
        val current = session()!!
        service.onTaskRemoved(Intent())
        service.onStartCommand(Intent().setAction("com.shilapi.xcertplay.browser.HTTPS_BACKGROUND_STOP"), 0, 1)
        assertFalse(current.isCancelled)
        assertEquals(0, closes.get())
        service.onStartCommand(Intent().setAction(BrowserHttpsVpnService.STOP), 0, 2)
        assertTrue(current.isCancelled)
        assertEquals(3, closes.get())
        assertFalse(current.own(Closeable { closes.incrementAndGet() }))
        assertEquals(4, closes.get())
        current.workerFinished()
    }
    private fun attachSyntheticResources(): AtomicInteger {
        val closes = AtomicInteger()
        val current = BrowserSession({ it.run() }, {})
        repeat(3) { current.own(Closeable { closes.incrementAndGet() }) }
        BrowserHttpsVpnService::class.java.getDeclaredField("session").apply { isAccessible = true }.set(service, current)
        return closes
    }
    private fun session() = BrowserHttpsVpnService::class.java.getDeclaredField("session").apply { isAccessible = true }.get(service) as BrowserSession?
}
