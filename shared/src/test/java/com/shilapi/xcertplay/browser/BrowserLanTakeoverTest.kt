package com.shilapi.xcertplay.browser

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Real loopback transport tests. The public RFC1918/origin policy is unchanged. */
class BrowserLanTakeoverTest {
    @Test fun replacementKeepsApprovedViewerUntilConsentThenRetiresItWithExactMarker() {
        Harness().use { h ->
            val old = h.connect().apply { upgrade(); approve() }
            val oldConnection = old.connection()
            val staleText = callback<(String) -> Unit>(oldConnection, "onText")
            val staleDisconnect = callback<() -> Unit>(oldConnection, "onDisconnected")
            val next = h.connect().apply { upgrade() }
            assertTrue(h.server.sendBinary(byteArrayOf(1), true))
            assertArrayEquals(byteArrayOf(1), old.readFrame().payload)
            val pending = next.requestApproval()
            old.send("old remains active")
            assertEquals("old remains active", h.texts.poll(2, TimeUnit.SECONDS))
            assertEquals(1, h.authenticated.get())
            assertTrue(pending.approve())
            next.expectAuthenticated(2)
            old.expectSuperseded()
            await { !h.tracked(oldConnection) }
            // Simulate callbacks already captured by the old reader before handover.
            staleText("stale touch")
            staleDisconnect()
            assertNull(h.texts.poll())
            assertEquals(0, h.disconnected.get())
            next.send("new viewer")
            assertEquals("new viewer", h.texts.poll(2, TimeUnit.SECONDS))
            assertTrue(h.server.sendBinary(byteArrayOf(2), true))
            assertArrayEquals(byteArrayOf(2), next.readFrame().payload)
            next.close()
            await { h.disconnected.get() == 1 }
            staleDisconnect()
            assertEquals(1, h.disconnected.get())
        }
    }

    @Test fun rejectedAndInvalidCandidatesCannotPreemptApprovedViewer() {
        Harness().use { h ->
            val old = h.connect().apply { upgrade(); approve() }
            val wrongOrigin = h.connect()
            wrongOrigin.writeUpgrade("https://untrusted.example")
            assertEquals(-1, wrongOrigin.socket.getInputStream().read())
            val rejected = h.connect().apply { upgrade() }
            rejected.requestApproval().reject()
            assertEquals("approvalRejected", rejected.closeReason())
            val invalid = h.connect().apply { upgrade() }
            invalid.send("""{"type":"touch","contacts":[]}""")
            assertEquals("upgradeRequired", invalid.closeReason())
            assertEquals(1, h.authenticated.get())
            assertEquals(0, h.disconnected.get())
            assertNull(h.approvals.poll())
            old.send("still active")
            assertEquals("still active", h.texts.poll(2, TimeUnit.SECONDS))
        }
    }

    @Test fun newestCandidateInvalidatesPendingRequestWithoutDisconnectingViewer() {
        Harness().use { h ->
            val old = h.connect().apply { upgrade(); approve() }
            val earlier = h.connect().apply { upgrade() }
            val earlierRequest = earlier.requestApproval()
            val latest = h.connect().apply { upgrade() }
            val latestRequest = latest.requestApproval()
            earlier.expectSuperseded()
            assertFalse(earlierRequest.approve())
            assertEquals(1, h.authenticated.get())
            assertEquals(0, h.disconnected.get())
            assertTrue(latestRequest.approve())
            latest.expectAuthenticated(2)
            old.expectSuperseded()
            assertEquals(0, h.disconnected.get())
        }
    }

    @Test fun olderSlowSocketCannotStealBackFromANewerApprovedConnection() {
        Harness().use { h ->
            val slow = h.connect().apply { upgrade() }
            val latest = h.connect().apply { upgrade(); approve() }
            slow.send("""{"type":"requestApproval","version":2}""")
            slow.expectSuperseded()
            assertNull(h.approvals.poll())
            assertEquals(1, h.authenticated.get())
            latest.send("latest stays")
            assertEquals("latest stays", h.texts.poll(2, TimeUnit.SECONDS))
        }
    }

    @Test fun approvalConsumedBeforeANewerCandidateCannotCommitLater() {
        Harness().use { h ->
            val original = h.connect().apply { upgrade(); approve() }
            val earlier = h.connect().apply { upgrade() }
            val earlierRequest = earlier.requestApproval()
            val inFinish = CountDownLatch(1)
            val releaseFinish = CountDownLatch(1)
            h.finishHook = { id -> if (id == earlierRequest.id) {
                inFinish.countDown()
                assertTrue(releaseFinish.await(3, TimeUnit.SECONDS))
            } }
            try {
                assertTrue(earlierRequest.approve())
                assertTrue(inFinish.await(2, TimeUnit.SECONDS))
                val latest = h.connect().apply { upgrade() }
                val latestRequest = latest.requestApproval()
                assertEquals(1, h.authenticated.get())
                assertTrue(latestRequest.approve())
                latest.expectAuthenticated(2)
                original.expectSuperseded()
                releaseFinish.countDown()
                earlier.expectSuperseded()
                await { !h.tracked(earlier.connection) }
                assertEquals(2, h.authenticated.get())
                assertEquals(0, h.disconnected.get())
            } finally { releaseFinish.countDown() }
        }
    }

    @Test fun takeoverWaitsForAnAlreadyRunningInputCallbackOnTheSharedLock() {
        Harness().use { h ->
            val original = h.connect().apply { upgrade(); approve() }
            val latest = h.connect().apply { upgrade() }
            val request = latest.requestApproval()
            val inputEntered = CountDownLatch(1)
            val releaseInput = CountDownLatch(1)
            val decisionConsumed = CountDownLatch(1)
            h.finishHook = { if (it == request.id) decisionConsumed.countDown() }
            h.textHook = {
                inputEntered.countDown()
                assertTrue(releaseInput.await(3, TimeUnit.SECONDS))
            }
            try {
                original.send("in-flight input")
                assertTrue(inputEntered.await(2, TimeUnit.SECONDS))
                assertTrue(request.approve())
                assertTrue(decisionConsumed.await(2, TimeUnit.SECONDS))
                assertEquals("No transport switch while application input is running", 1, h.authenticated.get())
                releaseInput.countDown()
                latest.expectAuthenticated(2)
                original.expectSuperseded()
            } finally { releaseInput.countDown() }
        }
    }

    @Test fun stoppedServerCannotCommitAnApprovalAlreadyConsumedByItsReader() {
        Harness().use { h ->
            val original = h.connect().apply { upgrade(); approve() }
            val next = h.connect().apply { upgrade() }
            val request = next.requestApproval()
            val inFinish = CountDownLatch(1)
            val releaseFinish = CountDownLatch(1)
            h.finishHook = { if (it == request.id) {
                inFinish.countDown()
                assertTrue(releaseFinish.await(3, TimeUnit.SECONDS))
            } }
            try {
                assertTrue(request.approve())
                assertTrue(inFinish.await(2, TimeUnit.SECONDS))
                h.server.close()
                releaseFinish.countDown()
                await { !h.tracked(next.connection) && !h.tracked(original.connection) }
                assertEquals(1, h.authenticated.get())
                assertEquals(1, h.disconnected.get())
            } finally { releaseFinish.countDown() }
        }
    }

    @Test fun retiringBlockedWriterRemainsTrackedUntilItsBoundedDeadline() {
        Harness().use { h ->
            val old = h.connect().apply { socket.receiveBufferSize = 1024; upgrade(); approve() }
            val connection = old.connection()
            assertTrue(h.server.sendBinary(ByteArray(BrowserLanProtocol.MAX_BINARY_BYTES), true))
            await { field(connection, "writingSince").getLong(connection) != 0L }
            val next = h.connect().apply { upgrade(); approve(2) }
            assertFalse(connection.isLive())
            h.server.checkDeadlines(System.nanoTime() / 1_000_000L + 1_100)
            await { !h.tracked(connection) }
            assertTrue(old.acceptedSocket.isClosed)
            assertEquals(0, h.disconnected.get())
            next.send("after blocked retire")
            assertEquals("after blocked retire", h.texts.poll(2, TimeUnit.SECONDS))
        }
    }

    @Test fun candidateSlotsRemainBoundedWhileApprovedViewerKeepsItsSlot() {
        Harness().use { h ->
            val original = h.connect().apply { upgrade(); approve() }
            val stalled = (0 until 4).map { h.connect() }
            val excess = h.connect(expectAdmitted = false)
            assertEquals(-1, excess.socket.getInputStream().read())
            assertEquals(5, h.connectionCount())
            original.send("owner survives cap")
            assertEquals("owner survives cap", h.texts.poll(2, TimeUnit.SECONDS))
            stalled.forEach { it.close() }
        }
    }

    private class Harness : AutoCloseable {
        val lock = Any()
        val approvals = LinkedBlockingQueue<BrowserApprovalRequest>()
        val texts = LinkedBlockingQueue<String>()
        val authenticated = AtomicInteger()
        val disconnected = AtomicInteger()
        @Volatile var finishHook: (Long) -> Unit = {}
        @Volatile var textHook: (String) -> Unit = {}
        private val clients = mutableListOf<Client>()
        lateinit var server: BrowserLanServer

        init {
            server = BrowserLanServer(InetAddress.getByName("192.168.40.2"), ORIGIN,
                onApprovalRequested = { approvals.add(it) },
                onApprovalFinished = { finishHook(it) },
                onAuthenticated = {
                    assertTrue(Thread.holdsLock(lock))
                    assertTrue(server.sendText("authenticated:${authenticated.incrementAndGet()}"))
                },
                onText = {
                    assertTrue(Thread.holdsLock(lock))
                    textHook(it)
                    texts.add(it)
                },
                onDisconnected = {
                    assertTrue(Thread.holdsLock(lock))
                    disconnected.incrementAndGet()
                }, lock = lock)
        }

        fun connect(expectAdmitted: Boolean = true): Client {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { listener ->
                val socket = Socket("127.0.0.1", listener.localPort).apply { soTimeout = 3_000 }
                val accepted = listener.accept()
                val connection = synchronized(lock) {
                    server.acceptConnection(accepted, HOST, privatePeer = true)
                    if (expectAdmitted) connections().maxByOrNull { it.sequence } else null
                }
                if (expectAdmitted) assertNotNull(connection)
                return Client(socket, accepted, connection, this).also(clients::add)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun connections(): Set<BrowserLanConnection> = field(server, "connections").get(server) as Set<BrowserLanConnection>
        fun tracked(connection: BrowserLanConnection?): Boolean = synchronized(lock) { connection != null && connections().contains(connection) }
        fun connectionCount(): Int = synchronized(lock) { connections().size }
        override fun close() {
            server.close()
            clients.forEach { it.close() }
            await { connectionCount() == 0 }
        }
    }

    private class Client(val socket: Socket, val acceptedSocket: Socket,
                         val connection: BrowserLanConnection?, val h: Harness) : AutoCloseable {
        fun connection(): BrowserLanConnection = connection!!
        fun writeUpgrade(origin: String = ORIGIN) {
            socket.getOutputStream().write(("GET /carplay HTTP/1.1\r\nHost: $HOST\r\nOrigin: $origin\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
        }
        fun upgrade() {
            writeUpgrade()
            val response = StringBuilder()
            while (!response.endsWith("\r\n\r\n")) {
                val next = socket.getInputStream().read()
                assertTrue(next >= 0)
                response.append(next.toChar())
                assertTrue(response.length < 1024)
            }
            assertTrue(response.startsWith("HTTP/1.1 101 "))
        }
        fun send(text: String) {
            val payload = text.toByteArray()
            assertTrue(payload.size < 126)
            val frame = ByteArrayOutputStream()
            frame.write(0x81)
            frame.write(0x80 or payload.size)
            val mask = byteArrayOf(1, 2, 3, 4)
            frame.write(mask)
            payload.forEachIndexed { i, value -> frame.write(value.toInt() xor mask[i and 3].toInt()) }
            socket.getOutputStream().write(frame.toByteArray())
        }
        fun requestApproval(): BrowserApprovalRequest {
            send("""{"type":"requestApproval","version":2}""")
            assertEquals("""{"type":"approvalPending","version":2}""", String(readFrame().payload))
            return h.approvals.poll(2, TimeUnit.SECONDS).also { assertNotNull(it) }!!
        }
        fun approve(number: Int = 1) {
            assertTrue(requestApproval().approve())
            expectAuthenticated(number)
        }
        fun expectAuthenticated(number: Int) { assertEquals("authenticated:$number", String(readFrame().payload)) }
        fun expectSuperseded() {
            val close = readFrame()
            assertEquals(8, close.opcode)
            assertArrayEquals(byteArrayOf(0x0f, 0xa1.toByte()) + "superseded".toByteArray(), close.payload)
        }
        fun closeReason(): String {
            val frame = readFrame()
            assertEquals(8, frame.opcode)
            return String(frame.payload.copyOfRange(2, frame.payload.size))
        }
        fun readFrame(): BrowserLanProtocol.Frame {
            val input = socket.getInputStream()
            val first = input.read()
            assertTrue(first >= 0)
            var length = input.read()
            assertTrue(length in 0..127)
            if (length == 126) length = (input.read() shl 8) or input.read()
            else if (length == 127) {
                var large = 0L
                repeat(8) { large = (large shl 8) or input.read().toLong() }
                assertTrue(large in 0..BrowserLanProtocol.MAX_BINARY_BYTES.toLong())
                length = large.toInt()
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
        override fun close() { socket.close() }
    }

    companion object {
        private const val HOST = "192.168.40.2:8765"
        private const val ORIGIN = "https://mark4z.github.io"
        private fun field(target: Any, name: String) = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        private fun <T> callback(target: Any, name: String): T = field(target, name).get(target) as T
        private fun await(condition: () -> Boolean) {
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (!condition() && System.nanoTime() < until) Thread.sleep(5)
            assertTrue("Condition did not become true before its deadline", condition())
        }
    }
}
