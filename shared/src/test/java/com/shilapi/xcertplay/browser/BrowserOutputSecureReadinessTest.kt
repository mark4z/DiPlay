package com.shilapi.xcertplay.browser

import java.io.ByteArrayInputStream
import java.net.InetAddress
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

/** No Android startup or trust override: install only an unbound synthetic server through reflection. */
class BrowserOutputSecureReadinessTest {
    companion object {
        @BeforeClass @JvmStatic fun syntheticFixtures() { BrowserTlsIdentityTest.syntheticFixtures() }
    }

    private val uiOwner = Any()
    private var shown = 0

    @Before fun reset() {
        BrowserOutput.stop()
        BrowserOutput.registerApprovalUi(uiOwner, { shown++ }, {})
    }

    @After fun cleanUp() {
        BrowserOutput.unregisterApprovalUi(uiOwner)
        BrowserOutput.stop()
    }

    @Test fun secureServerRejectsApprovalUntilItsMatchingSelfCheckMarksReady() {
        val identity = installSecureServer()
        val beforeCheck = request(1)
        dispatch(beforeCheck)
        assertEquals(2, beforeCheck.takeDecision())
        assertEquals(0, shown)
        assertFalse(BrowserOutput.isApprovalPending(beforeCheck))
        assertFalse(BrowserOutput.viewerConnected)
        assertFalse(BrowserOutput.browserTouchOwned)

        assertTrue(BrowserOutput.markSecureReady(identity))
        val afterCheck = request(2)
        dispatch(afterCheck)
        assertEquals(1, shown)
        assertTrue(BrowserOutput.isApprovalPending(afterCheck))
        assertEquals(0, afterCheck.takeDecision())
        assertFalse("Readiness must never approve a browser automatically", BrowserOutput.viewerConnected)
        assertFalse(BrowserOutput.browserTouchOwned)
    }

    @Test fun anotherIdentityAndAStoppedSessionCannotBecomeReady() {
        val identity = installSecureServer()
        val unrelated = BrowserTlsIdentityTest.syntheticIdentity()
        assertFalse(BrowserOutput.markSecureReady(unrelated))
        assertFalse(field("secureReady").getBoolean(null))
        val blocked = request(3)
        dispatch(blocked)
        assertEquals(2, blocked.takeDecision())

        assertTrue(BrowserOutput.markSecureReady(identity))
        BrowserOutput.stop()
        assertFalse(BrowserOutput.markSecureReady(identity))
        assertFalse(field("secureReady").getBoolean(null))
        assertNull(field("activeSecureIdentity").get(null))
        assertFalse(BrowserOutput.secure)
        assertNull(BrowserOutput.viewerUrl)
        assertNull(BrowserOutput.healthEndpoint)
    }

    @Test fun stopInvalidatesPendingSecureApprovalAndCallsSessionOwnerExactlyOnce() {
        val identity = installSecureServer()
        var stops = 0
        field("secureStopped").set(null, { stops++; Unit })
        assertTrue(BrowserOutput.markSecureReady(identity))
        val pending = request(4)
        dispatch(pending)
        assertTrue(BrowserOutput.isApprovalPending(pending))
        BrowserOutput.stop()
        BrowserOutput.stop()
        assertFalse(pending.approve())
        assertFalse(BrowserOutput.isApprovalPending(pending))
        assertEquals(1, stops)
        assertNull(field("secureStopped").get(null))
    }

    @Test fun oldGenerationCannotShowApprovalAfterAReplacementServerBecomesReady() {
        val oldIdentity = installSecureServer()
        assertTrue(BrowserOutput.markSecureReady(oldIdentity))
        val oldGeneration = generation()
        BrowserOutput.stop()
        val replacementIdentity = installSecureServer()
        assertTrue(BrowserOutput.markSecureReady(replacementIdentity))
        assertFalse(BrowserOutput.markSecureReady(oldIdentity))
        val stale = request(5)
        dispatch(stale, oldGeneration)
        assertEquals(2, stale.takeDecision())
        assertEquals(0, shown)
        val current = request(6)
        dispatch(current)
        assertEquals(1, shown)
        assertTrue(BrowserOutput.isApprovalPending(current))
    }

    @Test fun fixedSecureUrlsDoNotExposeAnIpOrPlaintextHealthEndpoint() {
        installSecureServer()
        assertTrue(BrowserOutput.secure)
        assertEquals("https://tesla.mark4z.asia:9999/", BrowserOutput.viewerUrl)
        assertEquals("https://tesla.mark4z.asia:9999/health", BrowserOutput.healthEndpoint)
        assertArrayEquals(BrowserLanProtocol.healthResponse(false), BrowserOutput.healthResponseForSelfCheck())
    }

    private fun installSecureServer(): BrowserTlsIdentity {
        val identity = BrowserTlsIdentityTest.syntheticIdentity()
        val assets = BrowserViewerAssets.load { ByteArrayInputStream("synthetic:$it".toByteArray()) }
        val server = BrowserLanServer(InetAddress.getByName(BrowserViewerAssets.ADDRESS),
            BrowserViewerAssets.ORIGIN, {}, {}, {}, {}, {}, secureIdentity = identity, viewerAssets = assets)
        field("serverGeneration").setLong(null, generation() + 1)
        field("server").set(null, server)
        field("endpoint").set(null, "wss://${BrowserViewerAssets.AUTHORITY}/carplay")
        field("activeSecureIdentity").set(null, identity)
        field("secureReady").setBoolean(null, false)
        return identity
    }
    private fun request(id: Long) = BrowserApprovalRequest(id, "192.168.40.5",
        System.nanoTime() / 1_000_000L + 30_000L)
    private fun field(name: String) = BrowserOutput::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun generation() = field("serverGeneration").getLong(null)
    private fun dispatch(request: BrowserApprovalRequest, generation: Long = generation()) {
        BrowserOutput::class.java.getDeclaredMethod("requestApproval", BrowserApprovalRequest::class.java,
            java.lang.Long.TYPE).apply { isAccessible = true }.invoke(BrowserOutput, request, generation)
    }
}
