package com.shilapi.xcertplay.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowserConnectionDiagnosticsTest {
    private val host = "192.168.40.2:41234"
    private val origin = "https://mark4z.github.io"

    @Test fun healthHasFixedBoundedBodyCorrectLengthAndHeadSemantics() {
        for (method in listOf("GET", "HEAD")) {
            val kind = classify(health(method)) as BrowserLanProtocol.HttpRequestKind.Health
            assertEquals(method == "HEAD", kind.headOnly)
            val response = String(BrowserLanProtocol.healthResponse(kind.headOnly), Charsets.UTF_8)
            val (headers, body) = response.split("\r\n\r\n", limit = 2)
            val expectedBody = "{\"service\":\"diplay-browser\",\"protocol\":2,\"build\":\"connection-diag-v1\"}\n"
            assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(headers.contains("Content-Type: application/json; charset=utf-8\r\n"))
            assertTrue(headers.contains("Content-Length: ${expectedBody.toByteArray().size}\r\n"))
            assertTrue(headers.contains("Connection: close\r\n"))
            assertTrue(headers.contains("Cache-Control: no-store\r\n"))
            assertEquals(if (method == "HEAD") "" else expectedBody, body)
            assertTrue(response.length < 512)
            assertFalse(response.contains("Access-Control-Allow-Origin"))
            assertFalse(response.contains(host))
        }
    }

    @Test fun healthOnlyAcceptsExactPathMethodHostOptionalPinnedOriginAndNoBodyOrUpgrade() {
        assertTrue(classify(health(extra = "Origin: $origin\r\n")) is BrowserLanProtocol.HttpRequestKind.Health)
        for (bad in listOf(
            health("POST"), health("OPTIONS"), health().replace("/health", "/health?token=secret"),
            health().replace("/health", "/health/"), health().replace("/health", "/health#fragment"),
            health().replace("/health", "/"), health().replace("HTTP/1.1", "HTTP/1.0"),
            health().replace(host, "evil.example.com"), health().replace("Host: $host\r\n", ""),
            health(extra = "Host: $host\r\n"), health(extra = "Origin: null\r\n"),
            health(extra = "Origin: https://evil.example.com\r\n"), health(extra = "Content-Length: 1\r\n"),
            health(extra = "Content-Length: 00\r\n"), health(extra = "Transfer-Encoding: chunked\r\n"),
            health(extra = "Upgrade: websocket\r\n"), health(extra = "Connection: keep-alive, Upgrade\r\n"),
            health(extra = "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"),
            health(extra = "X-Large: ${"x".repeat(8192)}\r\n"), health(extra = "X-Invalid : yes\r\n"),
            health().replace("\r\nHost:", "\nHost:"),
        )) assertThrows(BrowserLanProtocol.Failure::class.java) { classify(bad) }
        // Adding health cannot turn HEAD /carplay into a valid upgrade.
        assertThrows(BrowserLanProtocol.Failure::class.java) { classify(upgrade().replace("GET ", "HEAD ")) }
    }

    @Test fun websocketRejectionsKeepSpecificSafeClassifications() {
        val cases = listOf(
            upgrade().replace(host, "evil.example") to BrowserConnectionReason.INVALID_HOST,
            upgrade().replace(origin, "null") to BrowserConnectionReason.INVALID_ORIGIN,
            upgrade().replace("Version: 13", "Version: 12") to BrowserConnectionReason.INVALID_WEBSOCKET_VERSION,
            upgrade().replace("dGhlIHNhbXBsZSBub25jZQ==", "invalid") to BrowserConnectionReason.INVALID_WEBSOCKET_KEY,
            upgrade().replace("Connection: Upgrade", "Connection: close") to BrowserConnectionReason.INVALID_UPGRADE,
            upgrade().replace("GET ", "POST ") to BrowserConnectionReason.INVALID_METHOD,
            upgrade().replace("/carplay", "/carplay?credential=private") to BrowserConnectionReason.INVALID_PATH,
        )
        for ((request, reason) in cases) {
            val error = assertThrows(BrowserLanProtocol.Failure::class.java) { classify(request) }
            assertEquals(reason, error.reason)
            assertEquals("Invalid browser bridge protocol", error.message)
        }
    }

    @Test fun healthParsingConsumesOnlyOneHeaderBlockAndCannotGrantWebsocketApproval() {
        val input = ByteArrayInputStream(health().toByteArray() + upgrade().toByteArray())
        val parsed = BrowserLanProtocol.readHttpRequest(input)
        assertTrue(BrowserLanProtocol.classifyRequest(parsed, host, origin) is BrowserLanProtocol.HttpRequestKind.Health)
        assertEquals('G'.code, input.read())
        assertThrows(BrowserLanProtocol.Failure::class.java) {
            BrowserLanProtocol.handshake(ByteArrayInputStream(health().toByteArray()), host, origin)
        }
    }

    @Test fun healthDoesNotAcquireOwnershipRequestApprovalOrExposeDiagnostics() {
        Harness().use { h ->
            h.connect().use { client ->
                client.getOutputStream().write((health() + upgrade()).toByteArray())
                val response = client.getInputStream().readBytes().toString(Charsets.UTF_8)
                assertTrue(response.startsWith("HTTP/1.1 200 OK"))
                assertFalse(response.contains("101 Switching"))
                assertFalse(response.contains("TCP_ACCEPTED"))
                assertFalse(response.contains(host))
            }
            assertEquals(0, h.approvals.get())
            assertEquals(0, h.authenticated.get())
            assertFalse(h.server.sendText("must not send"))
            val events = h.server.diagnosticsSnapshot()
            assertEquals(listOf(BrowserConnectionStage.TCP_ACCEPTED, BrowserConnectionStage.HTTP_PARSED,
                BrowserConnectionStage.HEALTH_SERVED, BrowserConnectionStage.CLOSED), events.map { it.stage })
        }
    }

    @Test fun healthWorksWhileViewerOwnsSocketAndCannotStealOrCloseIt() {
        Harness().use { h ->
            val viewer = h.connect()
            h.upgrade(viewer)
            h.requestApproval(viewer)
            assertTrue(h.authentication.await(2, TimeUnit.SECONDS))
            for (method in listOf("GET", "HEAD")) h.connect().use { client ->
                client.getOutputStream().write(health(method).toByteArray())
                assertTrue(client.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 200 OK"))
            }
            h.connect().use { contender ->
                // An upgraded socket is only a candidate. Without its own valid
                // approval request and decision, it cannot replace this viewer.
                h.upgrade(contender)
            }
            // Reading HTTP 101 can precede its diagnostic write. Waiting for all
            // non-owner readers to retire synchronizes this check and proves cleanup.
            h.awaitConnectionCount(1)
            assertEquals(1, h.approvals.get())
            assertEquals(1, h.authenticated.get())
            assertEquals(0, h.disconnected.get())
            assertTrue(h.server.sendText("same viewer"))
            assertEquals("same viewer", h.readText(viewer))
            assertEquals(2, h.server.diagnosticsSnapshot().count {
                it.stage == BrowserConnectionStage.WEBSOCKET_ACCEPTED
            })
        }
    }

    @Test fun healthAlsoWorksWhileOriginalPromptIsPending() {
        Harness(autoApprove = false).use { h ->
            val viewer = h.connect()
            h.upgrade(viewer)
            h.requestApproval(viewer)
            assertTrue(h.prompt.await(2, TimeUnit.SECONDS))
            h.connect().use { client ->
                client.getOutputStream().write(health().toByteArray())
                assertTrue(client.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 200 OK"))
            }
            assertEquals(1, h.approvals.get())
            assertEquals(0, h.authenticated.get())
            assertTrue(h.request!!.approve())
            assertTrue(h.authentication.await(2, TimeUnit.SECONDS))
            assertEquals(1, h.authenticated.get())
        }
    }

    @Test fun stalledPreflightNeverOwnsViewerAndDeadlineClosesOnlyExpiredPreflight() {
        Harness().use { h ->
            val stalled = h.connect()
            stalled.getOutputStream().write("GET /he".toByteArray())
            val viewer = h.connect()
            h.upgrade(viewer); h.requestApproval(viewer)
            assertTrue(h.authentication.await(2, TimeUnit.SECONDS))
            // Receiving bytes can precede the writer clearing its in-flight timestamp.
            // Ensure the sole pending response has fully completed before moving time.
            h.awaitWriterIdle()
            h.server.checkDeadlines(System.nanoTime() / 1_000_000 + 5_100)
            assertEquals(-1, stalled.getInputStream().read())
            assertTrue(h.server.sendText("still approved"))
            assertEquals("still approved", h.readText(viewer))
            assertEquals(0, h.disconnected.get())
            assertTrue(h.server.diagnosticsSnapshot().any {
                it.stage == BrowserConnectionStage.DEADLINE && it.reason == BrowserConnectionReason.HEADER_DEADLINE
            })
        }
    }

    @Test fun concurrentPreflightsAreCappedAndServerCloseClosesEverySocket() {
        Harness().use { h ->
            val pending = (1..4).map { h.connect() }
            h.connect().use { rejected -> assertEquals(-1, rejected.getInputStream().read()) }
            assertTrue(h.server.diagnosticsSnapshot().any { it.reason == BrowserConnectionReason.PREFLIGHT_LIMIT })
            h.server.close()
            pending.forEach { assertEquals(-1, it.getInputStream().read()) }
            h.connect().use { rejected -> assertEquals(-1, rejected.getInputStream().read()) }
            assertTrue(h.server.diagnosticsSnapshot().any { it.reason == BrowserConnectionReason.SERVER_STOPPED })
            assertEquals(0, h.approvals.get())
        }
    }

    @Test fun nonPrivatePeerIsRejectedBeforeParsingOrApproval() {
        Harness().use { h ->
            h.connect(privatePeer = false).use { rejected -> assertEquals(-1, rejected.getInputStream().read()) }
            assertTrue(h.server.diagnosticsSnapshot().any { it.reason == BrowserConnectionReason.NON_PRIVATE_PEER })
            assertFalse(h.server.diagnosticsSnapshot().any { it.stage == BrowserConnectionStage.HTTP_PARSED })
            assertEquals(0, h.approvals.get())
        }
    }

    @Test fun shortLivedHealthProbesAreRateLimitedWithoutOpeningApproval() {
        Harness().use { h ->
            repeat(8) { index ->
                h.connect().use { client ->
                    client.getOutputStream().write(health().toByteArray())
                    val response = try {
                        client.getInputStream().readBytes().toString(Charsets.UTF_8)
                    } catch (failure: java.io.IOException) {
                        throw AssertionError("Health probe ${index + 1} transport failure: ${h.server.diagnosticsReport()}", failure)
                    }
                    assertTrue("Health probe ${index + 1}: response=${response.take(64)}; ${h.server.diagnosticsReport()}",
                        response.startsWith("HTTP/1.1 200 OK"))
                }
                // EOF precedes the reader's onFinished callback. Wait for its slot to
                // retire so this sequential rate-limit test cannot hit the preflight cap.
                h.awaitConnectionCount()
            }
            h.connect().use { rejected -> assertEquals(-1, rejected.getInputStream().read()) }
            assertTrue(h.server.diagnosticsSnapshot().any { it.reason == BrowserConnectionReason.RATE_LIMIT })
            assertEquals(0, h.approvals.get())
        }
    }

    @Test fun wrongOriginAndMalformedHandshakeHaveFixedReasonsAndNoPrivateContents() {
        val privateMarker = "PRIVATE_TOKEN_DO_NOT_LOG"
        Harness().use { h ->
            h.connect().use { client ->
                client.getOutputStream().write(upgrade().replace(origin, "https://$privateMarker.example").toByteArray())
                assertEquals(-1, client.getInputStream().read())
            }
            h.connect().use { client ->
                client.getOutputStream().write(health(extra = "X-$privateMarker: secret\r\nX-$privateMarker: again\r\n").toByteArray())
                assertEquals(-1, client.getInputStream().read())
            }
            val events = h.server.diagnosticsSnapshot()
            assertTrue(events.any { it.stage == BrowserConnectionStage.WEBSOCKET_REJECTED && it.reason == BrowserConnectionReason.INVALID_ORIGIN })
            assertTrue(events.any { it.stage == BrowserConnectionStage.HTTP_REJECTED && it.reason == BrowserConnectionReason.MALFORMED_HTTP })
            val report = h.server.diagnosticsReport()
            for (privateValue in listOf(privateMarker, host, origin, "secret", "127.0.0.1", "Sec-WebSocket-Key")) {
                assertFalse(privateValue, report.contains(privateValue))
            }
            assertEquals(0, h.approvals.get())
        }
    }

    @Test fun approvalLifecycleRecordsPromptAndApprovalButNeverFramePayload() {
        Harness().use { h ->
            val viewer = h.connect()
            h.upgrade(viewer); h.requestApproval(viewer)
            assertTrue(h.authentication.await(2, TimeUnit.SECONDS))
            viewer.getOutputStream().write(masked("PRIVATE_TOUCH_PAYLOAD"))
            assertTrue(h.text.await(2, TimeUnit.SECONDS))
            val stages = h.server.diagnosticsSnapshot().map { it.stage }
            assertTrue(stages.containsAll(listOf(BrowserConnectionStage.WEBSOCKET_ACCEPTED,
                BrowserConnectionStage.APPROVAL_REQUEST_RECEIVED, BrowserConnectionStage.PROMPT_PENDING,
                BrowserConnectionStage.PROMPT_SHOWN, BrowserConnectionStage.APPROVED)))
            assertFalse(h.server.diagnosticsReport().contains("PRIVATE_TOUCH_PAYLOAD"))
        }
    }

    @Test fun promptReportingIsOneShotAndObserverFailureDoesNotChangeApproval() {
        val request = BrowserApprovalRequest(1, "private-address", System.nanoTime() / 1_000_000 + 30_000)
        val reports = AtomicInteger()
        request.observePrompt { reports.incrementAndGet(); throw IllegalStateException("private exception") }
        request.markPromptUnavailable(); request.markPromptShown(); request.markPromptUnavailable()
        assertEquals(1, reports.get())
        assertTrue(request.approve())
        assertEquals(1, request.takeDecision())
    }

    @Test fun closedAttemptCannotAcquireLaterStagesFromRacingThreads() {
        val diagnostics = BrowserConnectionDiagnostics()
        val attempt = diagnostics.begin()
        attempt.record(BrowserConnectionStage.DEADLINE, BrowserConnectionReason.HEADER_DEADLINE)
        attempt.record(BrowserConnectionStage.CLOSED, BrowserConnectionReason.HEADER_DEADLINE)
        val frozen = diagnostics.snapshot()
        val workers = (1..8).map {
            Thread {
                repeat(100) {
                    attempt.record(BrowserConnectionStage.HTTP_PARSED)
                    attempt.record(BrowserConnectionStage.WEBSOCKET_ACCEPTED)
                    attempt.record(BrowserConnectionStage.PROMPT_PENDING)
                    attempt.record(BrowserConnectionStage.CLOSED)
                }
            }.apply { start() }
        }
        workers.forEach { it.join(5_000); assertFalse(it.isAlive) }
        assertEquals(frozen, diagnostics.snapshot())
        assertTrue(diagnostics.report().contains("CLOSED=1"))
        assertFalse(diagnostics.report().contains("PROMPT_PENDING"))
    }

    @Test fun ringIsBoundedThreadSafeAndRetainsOnlyNumericAndEnumEvents() {
        val diagnostics = BrowserConnectionDiagnostics()
        val workers = (1..8).map {
            Thread {
                repeat(200) {
                    diagnostics.begin().record(BrowserConnectionStage.CLOSED, BrowserConnectionReason.MALFORMED_HTTP)
                    assertTrue(diagnostics.snapshot().size <= 128)
                }
            }.apply { start() }
        }
        workers.forEach { it.join(5_000); assertFalse(it.isAlive) }
        val events = diagnostics.snapshot()
        assertEquals(128, events.size)
        assertTrue(events.all { it.connectionId > 0 && it.timestampMillis > 0 && it.elapsedMillis >= 0 })
        assertTrue(diagnostics.report().contains("TCP_ACCEPTED=1600"))
        assertTrue(diagnostics.report().length < 30_000)
        val snapshot = diagnostics.snapshot()
        diagnostics.begin()
        assertEquals(128, snapshot.size)
        for (field in BrowserConnectionEvent::class.java.declaredFields) {
            assertTrue(field.type == java.lang.Long.TYPE || field.type.isEnum)
        }
    }

    private fun classify(text: String) = BrowserLanProtocol.classifyRequest(
        BrowserLanProtocol.readHttpRequest(ByteArrayInputStream(text.toByteArray())), host, origin)

    private fun health(method: String = "GET", extra: String = "") =
        "$method /health HTTP/1.1\r\nHost: $host\r\n$extra\r\n"

    private fun upgrade() = "GET /carplay HTTP/1.1\r\nHost: $host\r\nOrigin: $origin\r\n" +
        "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n"

    private fun masked(text: String): ByteArray {
        val payload = text.toByteArray()
        require(payload.size < 126)
        return ByteArrayOutputStream().apply {
            write(0x81); write(0x80 or payload.size)
            val mask = byteArrayOf(1, 2, 3, 4)
            write(mask)
            payload.forEachIndexed { index, value -> write(value.toInt() xor mask[index and 3].toInt()) }
        }.toByteArray()
    }

    private inner class Harness(private val autoApprove: Boolean = true) : AutoCloseable {
        val approvals = AtomicInteger()
        val authenticated = AtomicInteger()
        val disconnected = AtomicInteger()
        val authentication = CountDownLatch(1)
        val prompt = CountDownLatch(1)
        val text = CountDownLatch(1)
        @Volatile var request: BrowserApprovalRequest? = null
        val server = BrowserLanServer(InetAddress.getByName("192.168.40.2"), origin,
            { request = it; approvals.incrementAndGet(); it.markPromptShown(); prompt.countDown(); if (autoApprove) it.approve() },
            {}, { authenticated.incrementAndGet(); authentication.countDown() }, { text.countDown() },
            { disconnected.incrementAndGet() })
        private val clients = mutableListOf<Socket>()

        fun connect(privatePeer: Boolean = true): Socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
            val client = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 3_000 }
            clients.add(client)
            server.acceptConnection(listener.accept(), host, privatePeer = privatePeer)
            client
        }

        fun upgrade(client: Socket) {
            client.getOutputStream().write(this@BrowserConnectionDiagnosticsTest.upgrade().toByteArray())
            val headers = StringBuilder()
            while (!headers.endsWith("\r\n\r\n")) {
                val next = client.getInputStream().read()
                assertTrue(next >= 0); headers.append(next.toChar()); assertTrue(headers.length < 1024)
            }
            assertTrue(headers.startsWith("HTTP/1.1 101 "))
        }

        fun requestApproval(client: Socket) {
            client.getOutputStream().write(masked("{\"type\":\"requestApproval\",\"version\":2}"))
            assertEquals("{\"type\":\"approvalPending\",\"version\":2}", readText(client))
        }

        fun awaitConnectionCount(expected: Int = 0) {
            val lock = BrowserLanServer::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(server)
            val connections = BrowserLanServer::class.java.getDeclaredField("connections").apply { isAccessible = true }
            fun pending(): Int = synchronized(lock) { (connections.get(server) as Set<*>).size }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (pending() != expected && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals("Connection cleanup did not finish: ${server.diagnosticsReport()}", expected, pending())
        }

        fun awaitWriterIdle() {
            val owner = BrowserLanServer::class.java.getDeclaredField("owner").apply { isAccessible = true }.get(server)
            val writingSince = BrowserLanConnection::class.java.getDeclaredField("writingSince").apply { isAccessible = true }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (writingSince.getLong(owner) != 0L && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals(0L, writingSince.getLong(owner))
        }

        fun readText(client: Socket): String {
            val input = client.getInputStream()
            assertEquals(0x81, input.read())
            val size = input.read()
            assertTrue(size in 0..125)
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val count = input.read(bytes, offset, size - offset)
                assertTrue(count > 0); offset += count
            }
            return bytes.toString(Charsets.UTF_8)
        }

        override fun close() { server.close(); clients.forEach { it.close() } }
    }
}

