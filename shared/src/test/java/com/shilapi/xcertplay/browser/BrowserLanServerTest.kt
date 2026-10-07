package com.shilapi.xcertplay.browser

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowserLanServerTest {
    private val origin = "https://viewer.example.com"
    private val host = "192.168.40.2:41234"
    private val token = "0123456789abcdefghijABCDEFGHIJ_-"

    @Test fun onlyRfc1918Ipv4IsAccepted() {
        for (address in listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.0.1")) {
            assertTrue(address, BrowserLanProtocol.isPrivateIpv4(InetAddress.getByName(address)))
        }
        for (address in listOf("0.0.0.0", "127.0.0.1", "8.8.8.8", "172.15.0.1", "172.32.0.1", "169.254.1.1", "100.64.0.1", "::1", "fd00::1")) {
            assertFalse(address, BrowserLanProtocol.isPrivateIpv4(InetAddress.getByName(address)))
        }
    }

    @Test fun publicConstructorNeverAllowsWildcardLoopbackOrPublicBinding() {
        for (address in listOf("0.0.0.0", "127.0.0.1", "8.8.8.8", "::1")) {
            assertThrows(IllegalArgumentException::class.java) {
                BrowserLanServer(InetAddress.getByName(address), "https://mark4z.github.io", {}, {}, {}, {}, {})
            }
        }
    }

    @Test fun publicServerPinsThePublishedViewerOrigin() {
        assertThrows(IllegalArgumentException::class.java) {
            BrowserLanServer(InetAddress.getByName("192.168.40.2"), origin, {}, {}, {}, {}, {})
        }
    }

    @Test fun originMustBeAnExactHttpsOrigin() {
        assertTrue(BrowserLanProtocol.isHttpsOrigin(origin))
        assertTrue(BrowserLanProtocol.isHttpsOrigin("https://viewer.example.com:8443"))
        for (value in listOf("*", "null", "http://viewer.example.com", "$origin/", "$origin/path", "$origin?x=1", "$origin#x", "https://user:pass@viewer.example.com", "https://*.example.com", "$origin:0", "$origin:65536", "$origin\r\n")) {
            assertFalse(value, BrowserLanProtocol.isHttpsOrigin(value))
        }
    }

    @Test fun handshakeMatchesRfcAcceptVectorWithoutConsumingFirstFrame() {
        val input = ByteArrayInputStream(request().toByteArray() + byteArrayOf(42))
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", BrowserLanProtocol.handshake(input, host, origin))
        assertEquals(42, input.read())
    }

    @Test fun handshakeRejectsOriginHostPathDuplicatesSmugglingAndWrongVersions() {
        for (bad in listOf(
            request(origin = "https://evil.example.com"),
            request(origin = "null"),
            request().replace("Origin: $origin\r\n", ""),
            request(host = "evil.example.com"),
            request().replace("/carplay", "/carplay?token=$token"),
            request().replace("GET ", "POST "),
            request().replace("HTTP/1.1", "HTTP/1.0"),
            request().replace("Version: 13", "Version: 12"),
            request().replace("Connection: keep-alive, Upgrade", "Connection: close"),
            request(extra = "Origin: $origin\r\n"),
            request(extra = "hOsT: $host\r\n"),
            request(extra = "Content-Length: 1\r\n"),
            request(extra = "Transfer-Encoding: chunked\r\n"),
            request().replace("Host:", " Host:"),
            request().replace("dGhlIHNhbXBsZSBub25jZQ==", "dGhl"),
        )) assertThrows(BrowserLanProtocol.Failure::class.java) {
            BrowserLanProtocol.handshake(ByteArrayInputStream(bad.toByteArray()), host, origin)
        }
    }

    @Test fun headerSizeAndCountAreBounded() {
        for (bad in listOf(request(extra = "X-Large: ${"x".repeat(8192)}\r\n"), request(extra = (0..65).joinToString("") { "X-$it: 1\r\n" }))) {
            assertThrows(BrowserLanProtocol.Failure::class.java) {
                BrowserLanProtocol.handshake(ByteArrayInputStream(bad.toByteArray()), host, origin)
            }
        }
    }

    @Test fun approvalRequestIsStrictAndLegacyTokensFailClosed() {
        fun valid(text: String) = BrowserLanProtocol.requestsApproval(text.toByteArray())
        assertTrue(valid("""{"type":"requestApproval","version":2}"""))
        assertTrue(valid(""" { "version": 2, "type": "requestApproval" } """))
        for (bad in listOf(
            """{"type":"auth","token":"$token"}""",
            """{"type":"requestApproval","version":1}""",
            """{"type":"requestApproval","version":"2"}""",
            """{"type":"requestApproval","version":2.0}""",
            """{"type":"requestApproval","version":2,"extra":1}""",
            """{"type":"requestApproval","type":"requestApproval"}""",
            """{"type":"requestApproval","version":2} garbage""",
        )) assertFalse(bad, valid(bad))
    }

    @Test fun readsMaskedTextAndControlFrames() {
        for (size in listOf(0, 1, 125, 126, 8192)) {
            val payload = ByteArray(size) { 'a'.code.toByte() }
            val frame = BrowserLanProtocol.readFrame(ByteArrayInputStream(masked(1, payload)))
            assertEquals(1, frame.opcode)
            assertArrayEquals(payload, frame.payload)
        }
        assertEquals(9, BrowserLanProtocol.readFrame(ByteArrayInputStream(masked(9, byteArrayOf(1, 2)))).opcode)
    }

    @Test fun rejectsUnmaskedFragmentedReservedAndBinaryFrames() {
        val valid = masked(1, "abc".toByteArray())
        for (bad in listOf(
            valid.copyOf().apply { this[1] = 3 },
            valid.copyOf().apply { this[0] = 1 },
            valid.copyOf().apply { this[0] = 0xc1.toByte() },
            valid.copyOf().apply { this[0] = 0x80.toByte() },
            valid.copyOf().apply { this[0] = 0x83.toByte() },
            masked(2, byteArrayOf()),
            masked(9, ByteArray(126)),
        )) assertThrows(BrowserLanProtocol.Failure::class.java) {
            BrowserLanProtocol.readFrame(ByteArrayInputStream(bad))
        }
    }

    @Test fun rejectsOversizeBeforeAllocatingOrReadingPayload() {
        for (bad in listOf(
            byteArrayOf(0x81.toByte(), 0xfe.toByte(), 0x20, 0x01), // 8193
            byteArrayOf(0x81.toByte(), 0xff.toByte(), 0, 0, 0, 1, 0, 0, 0, 0), // 4 GiB
        )) {
            val error = assertThrows(BrowserLanProtocol.Failure::class.java) {
                BrowserLanProtocol.readFrame(ByteArrayInputStream(bad))
            }
            assertEquals(1009, error.closeCode)
        }
    }

    @Test fun rejectsNoncanonicalLengthsAndInvalidUtf8() {
        for (bad in listOf(
            byteArrayOf(0x81.toByte(), 0xfe.toByte(), 0, 1),
            byteArrayOf(0x81.toByte(), 0xff.toByte(), 0, 0, 0, 0, 0, 0, 0, 126),
            byteArrayOf(0x81.toByte(), 0xff.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0),
            masked(1, byteArrayOf(0xc0.toByte(), 0xaf.toByte())),
        )) assertThrows(BrowserLanProtocol.Failure::class.java) {
            BrowserLanProtocol.readFrame(ByteArrayInputStream(bad))
        }
    }

    @Test fun validatesCloseCodesAndUtf8Reasons() {
        BrowserLanProtocol.validateClose(byteArrayOf())
        BrowserLanProtocol.validateClose(byteArrayOf(3, 0xe8.toByte()))
        BrowserLanProtocol.validateClose(byteArrayOf(0x0b, 0xb8.toByte()))
        for (bad in listOf(byteArrayOf(1), byteArrayOf(3, 0xee.toByte()), byteArrayOf(0, 1), byteArrayOf(3, 0xe8.toByte(), 0xff.toByte()))) {
            assertThrows(BrowserLanProtocol.Failure::class.java) { BrowserLanProtocol.validateClose(bad) }
        }
    }

    @Test fun outboundQueueCountsInflightFrameAndOwnsCopies() {
        val queue = BrowserLanQueue()
        val mutable = byteArrayOf(42)
        assertTrue(queue.offer(2, mutable, keyFrame = true))
        mutable[0] = 0
        val active = queue.take()!!
        assertEquals(42.toByte(), active.payload[0])
        assertTrue(queue.offer(2, byteArrayOf(1), keyFrame = true))
        assertTrue(queue.offer(2, byteArrayOf(2)))
        assertFalse(queue.offer(2, byteArrayOf(3)))
        queue.complete(active)
        assertFalse(queue.offer(2, byteArrayOf(3)))
        assertTrue(queue.offer(2, byteArrayOf(4), keyFrame = true))
        queue.close()
        assertNull(queue.take())
        assertFalse(queue.offer(2, byteArrayOf()))
    }

    @Test fun outboundBytesAndIndividualFramesAreBounded() {
        val queue = BrowserLanQueue()
        val max = ByteArray(4 * 1024 * 1024)
        assertFalse(queue.offer(2, ByteArray(max.size + 1)))
        assertTrue(queue.offer(2, max, keyFrame = true))
        val active = queue.take()!!
        assertTrue(queue.offer(2, max, keyFrame = true))
        assertFalse(queue.offer(2, byteArrayOf(1)))
        queue.complete(active)
        assertFalse(queue.offer(2, byteArrayOf(1)))
        assertTrue(queue.offer(2, byteArrayOf(1), keyFrame = true))
        queue.close()
    }

    @Test fun queueClosingDropsPendingVideoAndWakesWriter() {
        val queue = BrowserLanQueue()
        assertTrue(queue.offer(2, byteArrayOf(1), keyFrame = true))
        queue.finishWithClose(byteArrayOf(3, 0xe8.toByte()))
        assertFalse(queue.offer(2, byteArrayOf(2)))
        val closing = queue.take()!!
        assertEquals(8, closing.opcode)
        queue.complete(closing)
        assertNull(queue.take())
    }

    @Test fun rateLimitReopensOnlyAfterItsWindow() {
        val limit = BrowserLanRateLimit(2, 1000)
        val now = System.nanoTime() / 1_000_000
        assertTrue(limit.allow(now))
        assertTrue(limit.allow(now))
        assertFalse(limit.allow(now + 999))
        assertTrue(limit.allow(now + 1001))
    }

    @Test fun connectionGatesVideoAndInputUntilAuthThenReleasesOnDisconnect() {
        Harness().use { harness ->
            assertFalse(harness.connection.send(2, byteArrayOf(42)))
            harness.upgrade()
            assertFalse(harness.connection.send(2, byteArrayOf(42)))
            harness.authenticate()
            assertTrue(harness.connection.send(2, byteArrayOf(42), keyFrame = true))
            val video = harness.readServerFrame()
            assertEquals(2, video.opcode)
            assertArrayEquals(byteArrayOf(42), video.payload)
            harness.send(1, "{\"type\":\"touch\"}".toByteArray())
            assertTrue(harness.textReceived.await(2, TimeUnit.SECONDS))
            assertEquals(1, harness.textCount.get())
            harness.client.close()
            assertTrue(harness.finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, harness.disconnected.get())
            harness.connection.stop()
            assertEquals(1, harness.disconnected.get())
        }
    }

    @Test fun legacyTokenClosesWithoutMediaOrDisconnectCallback() {
        Harness().use { harness ->
            harness.upgrade()
            harness.send(1, "{\"type\":\"auth\",\"token\":\"incorrect\"}".toByteArray())
            assertTrue(harness.readCloseReason().endsWith("upgradeRequired"))
            assertTrue(harness.finished.await(2, TimeUnit.SECONDS))
            assertEquals(0, harness.authenticatedCount.get())
            assertEquals(0, harness.disconnected.get())
            assertFalse(harness.connection.send(2, byteArrayOf(1)))
        }
    }

    @Test fun touchAsFirstMessageIsRejectedAndNeverDelivered() {
        Harness().use { harness ->
            harness.upgrade()
            harness.send(1, "{\"type\":\"touch\",\"x\":1}".toByteArray())
            assertTrue(harness.readCloseReason().endsWith("upgradeRequired"))
            assertTrue(harness.finished.await(2, TimeUnit.SECONDS))
            assertEquals(0, harness.textCount.get())
            assertEquals(0, harness.authenticatedCount.get())
        }
    }

    @Test fun pendingApprovalHasNoMediaAndRejectIsOneShot() {
        Harness(false).use { h ->
            h.upgrade()
            h.send(1, """{"type":"requestApproval","version":2}""".toByteArray())
            assertTrue(h.approvalRequested.await(2, TimeUnit.SECONDS))
            assertEquals("""{"type":"approvalPending","version":2}""", String(h.readServerFrame().payload))
            assertFalse(h.connection.send(1, "private".toByteArray()))
            assertFalse(h.connection.send(2, byteArrayOf(42), keyFrame = true))
            assertEquals(0, h.authenticatedCount.get())
            h.approvalRequest!!.reject()
            assertFalse(h.approvalRequest!!.approve())
            assertTrue(h.readCloseReason().endsWith("approvalRejected"))
            assertTrue(h.finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, h.approvalFinished.get())
            assertEquals(0, h.disconnected.get())
        }
    }

    @Test fun pendingDisconnectInvalidatesExactRequestAndNewConnectionNeedsFreshApproval() {
        var stale: BrowserApprovalRequest? = null
        Harness(false).use { h ->
            h.upgrade(); h.send(1, """{"type":"requestApproval","version":2}""".toByteArray())
            assertTrue(h.approvalRequested.await(2, TimeUnit.SECONDS))
            stale = h.approvalRequest
            h.client.close()
            assertTrue(h.finished.await(2, TimeUnit.SECONDS))
            assertFalse(stale!!.approve())
            assertEquals(1, h.approvalFinished.get())
        }
        Harness(false).use { h ->
            h.upgrade(); h.send(1, """{"type":"requestApproval","version":2}""".toByteArray())
            assertTrue(h.approvalRequested.await(2, TimeUnit.SECONDS))
            h.readServerFrame()
            assertTrue(h.approvalRequest!!.id != stale!!.id)
            assertFalse(stale!!.approve())
            assertEquals(0, h.authenticatedCount.get())
            assertTrue(h.approvalRequest!!.approve())
            assertFalse(h.approvalRequest!!.approve())
            assertTrue(h.authenticated.await(2, TimeUnit.SECONDS))
        }
    }

    @Test fun preapprovalInputCancelsRequestAndNeverReachesApplication() {
        Harness(false).use { h ->
            h.upgrade(); h.send(1, """{"type":"requestApproval","version":2}""".toByteArray())
            assertTrue(h.approvalRequested.await(2, TimeUnit.SECONDS)); h.readServerFrame()
            h.send(1, """{"type":"touch","contacts":[]}""".toByteArray())
            assertTrue(h.finished.await(2, TimeUnit.SECONDS))
            assertFalse(h.approvalRequest!!.approve())
            assertEquals(0, h.textCount.get()); assertEquals(0, h.authenticatedCount.get())
        }
    }

    @Test fun stoppingFromFinishCallbackNeverAnnouncesAuthenticated() {
        Harness().use { h ->
            h.finishHook = { h.connection.stop() }
            h.upgrade(); h.send(1, """{"type":"requestApproval","version":2}""".toByteArray())
            assertTrue(h.finished.await(2, TimeUnit.SECONDS))
            assertEquals(0, h.authenticatedCount.get())
            assertEquals(1, h.approvalFinished.get())
            assertFalse(h.approvalRequest!!.approve())
            assertFalse(h.connection.send(2, byteArrayOf(42), keyFrame = true))
        }
    }

    @Test fun stoppingFromRequestCallbackInvalidatesBeforeLateApproval() {
        Harness().use { h ->
            h.requestHook = { h.connection.stop() }
            h.upgrade(); h.send(1, """{"type":"requestApproval","version":2}""".toByteArray())
            assertTrue(h.finished.await(2, TimeUnit.SECONDS))
            assertEquals(0, h.authenticatedCount.get())
            assertEquals(1, h.approvalFinished.get())
            assertFalse(h.approvalRequest!!.approve())
            assertEquals(0, h.disconnected.get())
        }
    }

    @Test fun pendingDeadlineClosesAndInvalidatesApproval() {
        Harness(false).use { h ->
            h.upgrade(); h.send(1, """{"type":"requestApproval","version":2}""".toByteArray())
            assertTrue(h.approvalRequested.await(2, TimeUnit.SECONDS)); h.readServerFrame()
            h.connection.checkDeadline(System.nanoTime() / 1_000_000 + 31_000)
            assertTrue(h.finished.await(2, TimeUnit.SECONDS))
            assertFalse(h.approvalRequest!!.approve()); assertEquals(0, h.authenticatedCount.get())
        }
    }

    @Test fun wrongOriginNeverUpgrades() {
        Harness().use { harness ->
            harness.client.getOutputStream().write(request(origin = "https://evil.example.com").toByteArray())
            assertEquals(-1, harness.client.getInputStream().read())
            assertTrue(harness.finished.await(2, TimeUnit.SECONDS))
            assertEquals(0, harness.authenticatedCount.get())
        }
    }

    @Test fun stalledHandshakeAndAuthExpireAndReleaseTheirSlot() {
        for (upgrade in listOf(false, true)) Harness().use { harness ->
            if (upgrade) harness.upgrade()
            harness.connection.checkDeadline(System.nanoTime() / 1_000_000 + 11_000)
            assertTrue(harness.finished.await(2, TimeUnit.SECONDS))
            assertEquals(0, harness.disconnected.get())
        }
    }

    @Test fun authenticatedIdleTimeoutDisconnectsExactlyOnce() {
        Harness().use { harness ->
            harness.upgrade()
            harness.authenticate()
            harness.connection.checkDeadline(System.nanoTime() / 1_000_000 + 31_000)
            assertTrue(harness.finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, harness.disconnected.get())
        }
    }

    @Test fun pingIsAnsweredAndCloseIsEchoed() {
        Harness().use { harness ->
            harness.upgrade()
            harness.authenticate()
            harness.send(9, byteArrayOf(1, 2, 3))
            val pong = harness.readServerFrame()
            assertEquals(10, pong.opcode)
            assertArrayEquals(byteArrayOf(1, 2, 3), pong.payload)
            harness.send(8, byteArrayOf(3, 0xe8.toByte()))
            assertEquals(8, harness.readServerFrame().opcode)
            assertTrue(harness.finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, harness.disconnected.get())
        }
    }

    @Test fun closingConnectionUnblocksBlockedReaderAndWriter() {
        Harness().use { harness ->
            harness.upgrade()
            harness.authenticate()
            // The client deliberately never reads this video.
            harness.connection.send(2, ByteArray(BrowserLanProtocol.MAX_BINARY_BYTES), keyFrame = true)
            harness.connection.stop()
            assertTrue(harness.finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, harness.disconnected.get())
            assertFalse(harness.connection.send(2, byteArrayOf(1)))
        }
    }

    private fun request(host: String = this.host, origin: String = this.origin, extra: String = "") =
        "GET /carplay HTTP/1.1\r\nHost: $host\r\nOrigin: $origin\r\n" +
            "Upgrade: websocket\r\nConnection: keep-alive, Upgrade\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n$extra\r\n"

    private fun masked(opcode: Int, payload: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        output.write(0x80 or opcode)
        if (payload.size < 126) output.write(0x80 or payload.size) else {
            output.write(0x80 or 126)
            output.write(payload.size ushr 8)
            output.write(payload.size)
        }
        val mask = byteArrayOf(1, 2, 3, 4)
        output.write(mask)
        payload.forEachIndexed { index, value -> output.write(value.toInt() xor mask[index and 3].toInt()) }
        return output.toByteArray()
    }

    private inner class Harness(private val approveAutomatically: Boolean = true) : AutoCloseable {
        var finishHook: () -> Unit = {}
        var requestHook: () -> Unit = {}
        @Volatile var approvalRequest: BrowserApprovalRequest? = null
        val approvalRequested = CountDownLatch(1)
        val approvalFinished = AtomicInteger()
        val authenticatedCount = AtomicInteger()
        val disconnected = AtomicInteger()
        val textCount = AtomicInteger()
        val authenticated = CountDownLatch(1)
        val textReceived = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val client: Socket
        val connection: BrowserLanConnection

        init {
            val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            try {
                client = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 3000 }
                connection = BrowserLanConnection(listener.accept(), host, origin,
                    { approvalRequest = it; approvalRequested.countDown(); requestHook(); if (approveAutomatically) it.approve() },
                    { approvalFinished.incrementAndGet(); finishHook() },
                    { authenticatedCount.incrementAndGet(); authenticated.countDown() },
                    { textCount.incrementAndGet(); textReceived.countDown() },
                    { disconnected.incrementAndGet() }, { finished.countDown() })
                connection.start()
            } finally { listener.close() }
        }

        fun upgrade() {
            client.getOutputStream().write(request().toByteArray())
            val response = StringBuilder()
            while (!response.endsWith("\r\n\r\n")) {
                val byte = client.getInputStream().read()
                assertTrue(byte >= 0)
                response.append(byte.toChar())
                assertTrue(response.length < 1024)
            }
            assertTrue(response.startsWith("HTTP/1.1 101 "))
        }

        fun authenticate() {
            send(1, "{\"type\":\"requestApproval\",\"version\":2}".toByteArray())
            assertEquals("{\"type\":\"approvalPending\",\"version\":2}", String(readServerFrame().payload))
            assertTrue(authenticated.await(2, TimeUnit.SECONDS))
        }

        fun send(opcode: Int, payload: ByteArray) { client.getOutputStream().write(masked(opcode, payload)) }

        fun readCloseReason(): String {
            var frame = readServerFrame()
            if (frame.opcode == 1) frame = readServerFrame()
            assertEquals(8, frame.opcode)
            return String(frame.payload.drop(2).toByteArray())
        }

        fun readServerFrame(): BrowserLanProtocol.Frame {
            val input = client.getInputStream()
            val first = input.read()
            assertTrue(first >= 0)
            var length = input.read()
            assertTrue(length in 0..127)
            if (length == 126) length = (input.read() shl 8) or input.read()
            else if (length == 127) {
                var longLength = 0L
                repeat(8) { longLength = (longLength shl 8) or input.read().toLong() }
                assertTrue(longLength <= BrowserLanProtocol.MAX_BINARY_BYTES)
                length = longLength.toInt()
            }
            val payload = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val count = input.read(payload, offset, length - offset)
                assertTrue(count > 0)
                offset += count
            }
            return BrowserLanProtocol.Frame(first and 15, payload)
        }

        override fun close() {
            client.close()
            connection.stop()
            assertTrue("Connection did not release its owner", finished.await(2, TimeUnit.SECONDS))
        }
    }
}
