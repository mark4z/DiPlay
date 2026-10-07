package com.shilapi.xcertplay.browser

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

/** Real TLS on a loopback test seam; all key/trust material is freshly generated in memory. */
class BrowserTlsTransportTest {
    companion object {
        @BeforeClass @JvmStatic fun syntheticFixtures() { BrowserTlsIdentityTest.syntheticFixtures() }
    }

    @Test fun tlsServesExactHealthAndApkAssetsWithoutApproval() {
        for ((path, head) in listOf("/health" to false, "/health" to true,
            "/" to false, "/viewer.mjs?v=embedded-https-v1" to false, "/viewer.css" to true)) {
            Harness().use { harness ->
                harness.handshake()
                harness.sendHttp(http(path, if (head) "HEAD" else "GET"))
                val expected = if (path == "/health") BrowserLanProtocol.healthResponse(head)
                    else BrowserViewerAssets.response(harness.assets.resource(path)!!, head)
                assertArrayEquals(expected, harness.readToClose())
                assertTrue(harness.finished.await(3, TimeUnit.SECONDS))
                assertEquals(0, harness.approvalCount.get())
                assertEquals(0, harness.authenticatedCount.get())
                assertEquals(0, harness.disconnectedCount.get())
            }
        }
    }

    @Test fun wrongAndMissingSniNeverReachHttpOrApproval() {
        for (name in listOf("other.mark4z.asia", null)) {
            Harness(sni = name).use { harness ->
                try {
                    harness.handshake()
                    harness.sendHttp(http("/"))
                } catch (timeout: SocketTimeoutException) {
                    throw AssertionError("SNI rejection must close rather than time out", timeout)
                } catch (_: IOException) {
                    // Providers may reject during the handshake or close after the exact-SNI check.
                }
                harness.assertClosedWithoutHttp()
                assertEquals(0, harness.approvalCount.get())
            }
        }
    }

    @Test fun tlsRejectsWrongHostOriginAndUnlistedPaths() {
        val requests = listOf(
            http("/", host = "100.99.9.9:9999"),
            http("/", host = BrowserViewerAssets.HOSTNAME),
            http("/", origin = "https://mark4z.github.io"),
            http("/%2e%2e/index.html"),
            http("/viewer.mjs?token=synthetic"),
            upgrade(origin = null),
            upgrade(origin = "https://mark4z.github.io"),
            upgrade(origin = "https://tesla.mark4z.asia"),
            upgrade(host = "100.99.9.9:9999"),
        )
        for (request in requests) Harness().use { harness ->
            harness.handshake()
            harness.sendHttp(request)
            harness.assertClosedWithoutHttp()
            assertEquals(0, harness.approvalCount.get())
            assertEquals(0, harness.authenticatedCount.get())
        }
    }

    @Test fun tlsUpgradeStillRequiresExplicitProtocolTwoApprovalBeforeMediaOrInput() {
        Harness().use { harness ->
            harness.handshake()
            harness.upgrade()
            assertFalse(harness.connection.send(1, "private-status".toByteArray()))
            assertFalse(harness.connection.send(2, byteArrayOf(42), keyFrame = true))
            harness.requestApproval()
            assertEquals(0, harness.authenticatedCount.get())
            assertEquals(0, harness.textCount.get())
            assertFalse(harness.connection.send(2, byteArrayOf(42), keyFrame = true))
            assertTrue(harness.approval!!.approve())
            assertTrue(harness.authenticated.await(3, TimeUnit.SECONDS))
            assertEquals(1, harness.authenticatedCount.get())
            assertTrue(harness.connection.send(1, "approved".toByteArray()))
            assertEquals("approved", String(harness.readFrame().payload, Charsets.UTF_8))
            assertTrue(harness.connection.send(2, byteArrayOf(42), keyFrame = true))
            val video = harness.readFrame()
            assertEquals(2, video.opcode)
            assertArrayEquals(byteArrayOf(42), video.payload)
            harness.sendFrame(1, "approved-input".toByteArray())
            assertTrue(harness.textReceived.await(3, TimeUnit.SECONDS))
            assertEquals(1, harness.textCount.get())
            harness.connection.stop()
            assertTrue(harness.finished.await(3, TimeUnit.SECONDS))
            assertEquals(1, harness.disconnectedCount.get())
        }
    }

    @Test fun legacyApprovalPayloadCannotAuthenticateOverTls() {
        Harness().use { harness ->
            harness.handshake()
            harness.upgrade()
            harness.sendFrame(1, "{\"type\":\"auth\",\"token\":\"synthetic\"}".toByteArray())
            assertTrue(harness.finished.await(3, TimeUnit.SECONDS))
            assertEquals(0, harness.approvalCount.get())
            assertEquals(0, harness.authenticatedCount.get())
            assertEquals(0, harness.textCount.get())
        }
    }

    @Test fun stopDuringTlsHandshakeReleasesReaderWithoutApproval() {
        Harness().use { harness ->
            // The client deliberately sends no TLS ClientHello.
            harness.awaitHandshakeStarted()
            harness.connection.stop()
            assertTrue(harness.finished.await(3, TimeUnit.SECONDS))
            assertEquals(0, harness.approvalCount.get())
            assertEquals(0, harness.authenticatedCount.get())
        }
    }

    @Test fun absoluteHeaderDeadlineAlsoClosesAnUnfinishedTlsHandshake() {
        Harness().use { harness ->
            harness.awaitHandshakeStarted()
            harness.connection.checkDeadline(System.nanoTime() / 1_000_000L + 5_001L)
            assertTrue(harness.finished.await(3, TimeUnit.SECONDS))
            assertEquals(0, harness.approvalCount.get())
        }
    }

    @Test fun stopDuringApprovalInvalidatesGrantAndFinishesItOnce() {
        Harness().use { harness ->
            harness.handshake()
            harness.upgrade()
            harness.requestApproval()
            harness.connection.stop()
            assertTrue(harness.finished.await(3, TimeUnit.SECONDS))
            assertFalse(harness.approval!!.approve())
            assertEquals(1, harness.approvalFinishedCount.get())
            assertEquals(0, harness.authenticatedCount.get())
            assertEquals(0, harness.disconnectedCount.get())
            harness.connection.stop()
            assertEquals(1, harness.approvalFinishedCount.get())
        }
    }

    @Test fun tlsWriterDeadlineClosesRawTransportWhenPeerStopsReading() {
        Harness().use { harness ->
            harness.handshake()
            harness.upgrade()
            harness.requestApproval()
            assertTrue(harness.approval!!.approve())
            assertTrue(harness.authenticated.await(3, TimeUnit.SECONDS))
            // Larger than the small receive window; leave the TLS peer unread.
            assertTrue(harness.connection.send(2, ByteArray(BrowserLanProtocol.MAX_BINARY_BYTES), keyFrame = true))
            val writing = BrowserLanConnection::class.java.getDeclaredField("writingSince").apply { isAccessible = true }
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            var writeStarted: Long
            do {
                writeStarted = writing.getLong(harness.connection)
                if (writeStarted == 0L) Thread.sleep(5)
            } while (writeStarted == 0L && System.nanoTime() < until)
            assertTrue("TLS writer never began", writeStarted != 0L)
            Thread.sleep(50)
            assertEquals("Peer must keep the TLS write in flight", writeStarted, writing.getLong(harness.connection))
            val deadlineReturned = CountDownLatch(1)
            Thread({
                try { harness.connection.checkDeadline(writeStarted + 5_001L) }
                finally { deadlineReturned.countDown() }
            }, "tls-test-deadline").apply { isDaemon = true; start() }
            assertTrue("TLS close blocked the deadline worker", deadlineReturned.await(3, TimeUnit.SECONDS))
            assertTrue("TLS reader stayed blocked after raw close", harness.finished.await(3, TimeUnit.SECONDS))
            assertEquals(1, harness.disconnectedCount.get())
        }
    }

    private fun http(path: String, method: String = "GET", host: String = BrowserViewerAssets.AUTHORITY,
                     origin: String? = null): String =
        "$method $path HTTP/1.1\r\nHost: $host\r\n" +
            (origin?.let { "Origin: $it\r\n" } ?: "") + "Connection: close\r\n\r\n"

    private fun upgrade(host: String = BrowserViewerAssets.AUTHORITY,
                        origin: String? = BrowserViewerAssets.ORIGIN): String =
        "GET /carplay HTTP/1.1\r\nHost: $host\r\n" +
            (origin?.let { "Origin: $it\r\n" } ?: "") +
            "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n"

    private inner class Harness(sni: String? = BrowserViewerAssets.HOSTNAME) : AutoCloseable {
        val assets = BrowserViewerAssets.load { ByteArrayInputStream("synthetic:$it".toByteArray()) }
        val approvalCount = AtomicInteger()
        val approvalFinishedCount = AtomicInteger()
        val authenticatedCount = AtomicInteger()
        val disconnectedCount = AtomicInteger()
        val textCount = AtomicInteger()
        val requested = CountDownLatch(1)
        val authenticated = CountDownLatch(1)
        val textReceived = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val diagnostics = BrowserConnectionDiagnostics()
        @Volatile var approval: BrowserApprovalRequest? = null
        val client: SSLSocket
        val connection: BrowserLanConnection

        init {
            val identity = BrowserTlsIdentityTest.syntheticIdentity()
            val listener = ServerSocket()
            val raw = Socket().apply { receiveBufferSize = 1024 }
            try {
                listener.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1)
                raw.connect(InetSocketAddress("127.0.0.1", listener.localPort), 3_000)
                client = (BrowserTlsIdentityTest.syntheticClientContext().socketFactory
                    .createSocket(raw, if (sni == null) "127.0.0.1" else BrowserViewerAssets.HOSTNAME, listener.localPort, true) as SSLSocket).apply {
                    soTimeout = 3_000
                    val parameters = sslParameters
                    // A named peer can automatically recreate SNI even after an empty list.
                    // Only the missing-SNI negative fixture uses a numeric peer/no identity check;
                    // it must still be rejected by the server before any HTTP or approval.
                    parameters.endpointIdentificationAlgorithm = if (sni == null) null else "HTTPS"
                    parameters.serverNames = sni?.let { listOf(SNIHostName(it)) } ?: emptyList()
                    sslParameters = parameters
                }
                connection = BrowserLanConnection(listener.accept(), BrowserViewerAssets.AUTHORITY,
                    BrowserViewerAssets.ORIGIN,
                    onApprovalRequested = { approval = it; approvalCount.incrementAndGet(); requested.countDown() },
                    onApprovalFinished = { approvalFinishedCount.incrementAndGet() },
                    onAuthenticated = { authenticatedCount.incrementAndGet(); authenticated.countDown() },
                    onText = { textCount.incrementAndGet(); textReceived.countDown() },
                    onDisconnected = { disconnectedCount.incrementAndGet() },
                    onFinished = { finished.countDown() }, diagnostic = diagnostics.begin(), claimViewer = { true },
                    viewerAssets = assets, secureIdentity = identity)
                connection.start()
            } catch (failure: Throwable) {
                raw.close()
                throw failure
            } finally { listener.close() }
        }

        fun handshake() { client.startHandshake() }
        fun awaitHandshakeStarted() {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (diagnostics.snapshot().none { it.stage == BrowserConnectionStage.TLS_HANDSHAKE_STARTED } &&
                System.nanoTime() < until) Thread.sleep(5)
            assertTrue("Server did not begin TLS handshake",
                diagnostics.snapshot().any { it.stage == BrowserConnectionStage.TLS_HANDSHAKE_STARTED })
        }
        fun sendHttp(request: String) {
            client.outputStream.write(request.toByteArray(Charsets.US_ASCII))
            client.outputStream.flush()
        }
        fun upgrade() {
            sendHttp(this@BrowserTlsTransportTest.upgrade())
            val headers = StringBuilder()
            while (!headers.endsWith("\r\n\r\n")) {
                val byte = client.inputStream.read()
                assertTrue("Expected a complete WebSocket upgrade", byte >= 0)
                headers.append(byte.toChar())
                assertTrue(headers.length < 1024)
            }
            assertTrue(headers.startsWith("HTTP/1.1 101 Switching Protocols\r\n"))
        }
        fun requestApproval() {
            sendFrame(1, "{\"type\":\"requestApproval\",\"version\":2}".toByteArray())
            assertTrue(requested.await(3, TimeUnit.SECONDS))
            assertEquals("{\"type\":\"approvalPending\",\"version\":2}", String(readFrame().payload, Charsets.UTF_8))
        }
        fun sendFrame(opcode: Int, payload: ByteArray) {
            check(payload.size < 126)
            val output = ByteArrayOutputStream()
            output.write(0x80 or opcode)
            output.write(0x80 or payload.size)
            val mask = byteArrayOf(1, 2, 3, 4)
            output.write(mask)
            payload.forEachIndexed { index, value -> output.write(value.toInt() xor mask[index and 3].toInt()) }
            client.outputStream.write(output.toByteArray())
            client.outputStream.flush()
        }
        fun readFrame(): BrowserLanProtocol.Frame {
            val input = client.inputStream
            val first = input.read()
            val length = input.read()
            assertTrue(first >= 0)
            assertTrue("Small unmasked server frame expected", length in 0..125)
            val bytes = ByteArray(length)
            for (index in bytes.indices) {
                val byte = input.read()
                assertTrue(byte >= 0)
                bytes[index] = byte.toByte()
            }
            return BrowserLanProtocol.Frame(first and 15, bytes)
        }
        fun readToClose(): ByteArray {
            val response = ByteArrayOutputStream()
            try {
                while (true) {
                    val byte = client.inputStream.read()
                    if (byte < 0) break
                    response.write(byte)
                    assertTrue(response.size() <= BrowserViewerAssets.MAX_FILE_BYTES + 2048)
                }
            } catch (timeout: SocketTimeoutException) {
                throw AssertionError("HTTP response did not complete", timeout)
            } catch (_: IOException) {
                // Raw-first teardown can omit close_notify. Exact response comparison below
                // still detects truncation, and must not depend on the JVM's EOF policy.
            }
            return response.toByteArray()
        }
        fun assertClosedWithoutHttp() {
            assertTrue("Rejected TLS request did not finish", finished.await(3, TimeUnit.SECONDS))
            try {
                assertEquals("Rejected request must not receive an HTTP response", -1, client.inputStream.read())
            } catch (timeout: SocketTimeoutException) {
                throw AssertionError("Rejected request stayed open", timeout)
            } catch (_: IOException) {
                // TLS fatal alerts and an abruptly closed TLS connection are both fail-closed.
            }
        }
        override fun close() {
            try { runCatching { client.close() } } finally { connection.stop() }
            assertTrue("TLS reader did not release the connection", finished.await(3, TimeUnit.SECONDS))
        }
    }
}
