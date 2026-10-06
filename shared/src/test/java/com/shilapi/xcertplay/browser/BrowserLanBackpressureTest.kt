package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class BrowserLanBackpressureTest {
    @Test fun producerRequestsRecoveryImmediatelyAndRateLimitsRetriesWhileNativeTeeContinues() {
        BrowserOutput.stop()
        val nativeFrames = AtomicInteger()
        val recoveryCalls = AtomicInteger()
        val recoveryStarted = CountDownLatch(1)
        val releaseRecovery = CountDownLatch(1)
        val native = object : MediaSink {
            override fun onVideoFrame(type: Int, naluBytes: ByteArray) { nativeFrames.incrementAndGet() }
        }
        val tee = BrowserOutput.tee(native, 1280, 720, {}, {})
        tee.onVideoCodec(110, VideoCodec.H264)
        tee.onVideoConfig(110, byteArrayOf(1,100,0,42,-1,-31,0,4,103,100,0,42,1,0,2,104,1))
        tee.setVideoRecoveryHandler(110) {
            recoveryCalls.incrementAndGet()
            recoveryStarted.countDown()
            check(releaseRecovery.await(2, TimeUnit.SECONDS))
        }
        try {
            MemoryConnection().use { session ->
                // Install an already-authenticated in-memory connection, without changing
                // the public LAN bind policy or adding a production-only testing hook.
                val server = BrowserLanServer(InetAddress.getByName("192.168.40.2"), "https://mark4z.github.io", {}, {}, {}, {}, {})
                BrowserLanServer::class.java.getDeclaredField("owner").apply { isAccessible = true }.set(server, session.connection)
                outputField("server").set(null, server)
                outputField("viewerConnected").setBoolean(null, true)
                outputField("lastRecoveryNs").setLong(null, System.nanoTime() - 2_000_000_000L)
                fun frame(marker: Int, key: Boolean = false) = tee.onVideoFrame(110,
                    byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41, marker.toByte()))
                session.blockWriter()
                frame(1, key = true)
                session.awaitBlocked()
                frame(2); frame(3); frame(4)
                assertTrue("Dropped frame did not immediately request recovery", recoveryStarted.await(1, TimeUnit.SECONDS))
                repeat(100) { frame(5) }
                assertEquals(1, recoveryCalls.get())
                assertTrue(outputField("waitingForKey").getBoolean(null))
                assertTrue(BrowserOutput.viewerConnected)
                assertFalse(session.socket.closed)
                frame(6, key = true); frame(7)
                assertFalse(outputField("waitingForKey").getBoolean(null))
                session.releaseWriter()
                val frames = List(3) { session.nextFrame() }
                assertEquals(listOf(1, 6, 7), frames.map { it.payload.last().toInt() })
                assertEquals(listOf(1, 1, 2), frames.map { it.payload[0].toInt() })
                session.assertNoMoreFrames()
                assertEquals(106, nativeFrames.get())
                releaseRecovery.countDown()
                (outputField("recoveryExecutor").get(null) as ExecutorService).submit {}.get(2, TimeUnit.SECONDS)
                // A second lost dependency inside the one-second window cannot issue
                // another request even after the first recovery callback has returned.
                outputField("lastRecoveryNs").setLong(null, System.nanoTime())
                tee.onVideoFrame(110, byteArrayOf())
                (outputField("recoveryExecutor").get(null) as ExecutorService).submit {}.get(2, TimeUnit.SECONDS)
                assertEquals(1, recoveryCalls.get())
            }
        } finally {
            releaseRecovery.countDown()
            BrowserOutput.stop()
        }
    }

    @Test fun burstDropsDependenciesWithoutDisconnectAndResumesAtFreshKeyframe() {
        MemoryConnection().use { session ->
            session.blockWriter()
            assertTrue(session.sendVideo(1, key = true))
            session.awaitBlocked()
            assertTrue(session.sendVideo(2))
            assertTrue(session.sendVideo(3))
            assertFalse(session.sendVideo(4))
            repeat(100) { assertFalse(session.sendVideo(5)) }
            assertFalse(session.socket.closed)
            assertEquals(0, session.disconnected.get())
            assertTrue(session.sendVideo(6, key = true))
            assertTrue(session.sendVideo(7))
            session.releaseWriter()
            assertEquals(listOf(1, 6, 7), List(3) { session.nextFrame().payload[0].toInt() })
            session.assertNoMoreFrames()
            assertFalse(session.socket.closed)
        }
    }

    @Test fun configBoundaryFinishesInflightVideoThenConfigThenOnlyNewGeneration() {
        MemoryConnection().use { session ->
            session.blockWriter()
            assertTrue(session.sendVideo(1, key = true))
            session.awaitBlocked()
            assertTrue(session.sendVideo(2))
            assertTrue(session.sendVideo(3))
            val notice = "older-control".toByteArray()
            assertTrue(session.connection.send(1, notice))
            val config = "new-generation-config".toByteArray()
            assertTrue(session.connection.send(1, config, resetVideo = true))
            assertFalse(session.sendVideo(4))
            assertTrue(session.sendVideo(5, key = true))
            assertTrue(session.sendVideo(6))
            session.releaseWriter()
            assertEquals(1, session.nextFrame().payload[0].toInt())
            val olderControl = session.nextFrame()
            assertEquals(1, olderControl.opcode)
            assertArrayEquals(notice, olderControl.payload)
            val boundary = session.nextFrame()
            assertEquals(1, boundary.opcode)
            assertArrayEquals(config, boundary.payload)
            assertEquals(5, session.nextFrame().payload[0].toInt())
            assertEquals(6, session.nextFrame().payload[0].toInt())
            session.assertNoMoreFrames()
        }
    }

    @Test fun pingAndPongUseIndependentCreditsAndPrecedePendingVideo() {
        MemoryConnection().use { session ->
            session.blockWriter()
            assertTrue(session.sendVideo(1, key = true))
            session.awaitBlocked()
            assertTrue(session.sendVideo(2))
            assertTrue(session.sendVideo(3))
            // The writer is still below its timeout; only move the ping clock back.
            session.setClock("lastPing", session.now() - 10_001)
            session.connection.checkDeadline()
            session.socket.receive(masked(9, byteArrayOf(42)))
            // onText runs after the prior ping has queued its pong, giving a reader barrier.
            session.socket.receive(masked(1, "barrier".toByteArray()))
            assertTrue(session.textReceived.await(2, TimeUnit.SECONDS))
            assertFalse(session.socket.closed)
            session.releaseWriter()
            assertEquals(1, session.nextFrame().payload[0].toInt())
            assertEquals(9, session.nextFrame().opcode)
            val pong = session.nextFrame()
            assertEquals(10, pong.opcode)
            assertArrayEquals(byteArrayOf(42), pong.payload)
            assertEquals(2, session.nextFrame().payload[0].toInt())
            assertEquals(3, session.nextFrame().payload[0].toInt())
        }
    }

    @Test fun repeatedBurstsCannotResetTheFiveSecondBlockedWriterDeadline() {
        MemoryConnection().use { session ->
            session.blockWriter()
            assertTrue(session.sendVideo(1, key = true))
            session.awaitBlocked()
            repeat(100) {
                assertTrue(session.sendVideo(2, key = true))
                session.sendVideo(3)
                session.sendVideo(4)
            }
            val writeStart = session.clock("writingSince")
            session.connection.checkDeadline(writeStart + 4_999)
            assertFalse(session.socket.closed)
            session.connection.checkDeadline(writeStart + 5_000)
            assertTrue(session.finished.await(2, TimeUnit.SECONDS))
            assertTrue(session.socket.closed)
            assertEquals(1, session.disconnected.get())
            session.connection.checkDeadline(writeStart + 6_000)
            assertEquals(1, session.disconnected.get())
        }
    }

    @Test fun videoCreditsStayBoundedAcrossRepeatedKeyframeReplacement() {
        val queue = BrowserLanQueue()
        val max = ByteArray(BrowserLanProtocol.MAX_BINARY_BYTES)
        assertTrue(queue.offer(2, max, keyFrame = true))
        val active = queue.take()!!
        repeat(20) { value ->
            max[0] = value.toByte()
            assertTrue(queue.offer(2, max, keyFrame = true))
            // The active 4 MiB + queued 4 MiB fills the byte budget, even with <3 frames.
            assertFalse(queue.offer(2, byteArrayOf(99)))
            assertFalse(queue.offer(2, byteArrayOf(100)))
        }
        assertTrue(queue.offer(2, byteArrayOf(101), keyFrame = true))
        queue.complete(active)
        val recovered = queue.take()!!
        assertArrayEquals(byteArrayOf(101), recovered.payload)
        queue.complete(recovered)
        queue.close()
        assertNull(queue.take())
    }

    @Test fun successiveConfigBoundariesDiscardThePreviousGenerationsQueuedKeyframe() {
        val queue = BrowserLanQueue()
        assertTrue(queue.offer(2, byteArrayOf(1), keyFrame = true))
        val active = queue.take()!!
        assertTrue(queue.offer(1, byteArrayOf(20), resetVideo = true))
        assertTrue(queue.offer(2, byteArrayOf(2), keyFrame = true))
        assertTrue(queue.offer(2, byteArrayOf(3)))
        assertTrue(queue.offer(1, byteArrayOf(21), resetVideo = true))
        assertFalse(queue.offer(2, byteArrayOf(4)))
        assertTrue(queue.offer(2, byteArrayOf(5), keyFrame = true))
        queue.complete(active)
        for ((opcode, marker) in listOf(1 to 20, 1 to 21, 2 to 5)) {
            val frame = queue.take()!!
            assertEquals(opcode, frame.opcode)
            assertArrayEquals(byteArrayOf(marker.toByte()), frame.payload)
            queue.complete(frame)
        }
        queue.close()
        assertNull(queue.take())
    }

    @Test fun incomingKeyframeCanReplaceFullVideoQueueWithoutLosingControl() {
        val queue = BrowserLanQueue()
        assertTrue(queue.offer(2, byteArrayOf(1), keyFrame = true))
        val active = queue.take()!!
        assertTrue(queue.offer(2, byteArrayOf(2)))
        assertTrue(queue.offer(2, byteArrayOf(3)))
        assertTrue(queue.offer(1, byteArrayOf(42)))
        assertTrue(queue.offer(2, byteArrayOf(4), keyFrame = true))
        queue.complete(active)
        val control = queue.take()!!
        assertEquals(1, control.opcode)
        assertArrayEquals(byteArrayOf(42), control.payload)
        queue.complete(control)
        val video = queue.take()!!
        assertArrayEquals(byteArrayOf(4), video.payload)
        queue.complete(video)
        queue.close()
        assertNull(queue.take())
    }

    @Test fun controlCountAndBytesIncludeInflightWithoutBorrowingVideoCredits() {
        val countQueue = BrowserLanQueue()
        val source = byteArrayOf(42)
        assertTrue(countQueue.offer(10, source))
        source[0] = 0
        val active = countQueue.take()!!
        assertArrayEquals(byteArrayOf(42), active.payload)
        repeat(15) { assertTrue(countQueue.offer(9, byteArrayOf())) }
        assertFalse(countQueue.offer(9, byteArrayOf()))
        assertTrue(countQueue.offer(2, byteArrayOf(1), keyFrame = true))
        countQueue.complete(active)
        assertTrue(countQueue.offer(9, byteArrayOf()))
        countQueue.close()
        assertNull(countQueue.take())

        val byteQueue = BrowserLanQueue()
        val text = ByteArray(BrowserLanProtocol.MAX_TEXT_BYTES)
        assertFalse(byteQueue.offer(1, ByteArray(text.size + 1)))
        assertFalse(byteQueue.offer(9, ByteArray(126)))
        assertTrue(byteQueue.offer(1, text))
        val writing = byteQueue.take()!!
        repeat(7) { assertTrue(byteQueue.offer(1, text)) }
        assertFalse(byteQueue.offer(1, byteArrayOf(1)))
        assertTrue(byteQueue.offer(2, byteArrayOf(1), keyFrame = true))
        byteQueue.complete(writing)
        assertTrue(byteQueue.offer(1, text))
        byteQueue.close()
    }

    @Test fun overflowingControlStillClosesRatherThanAccumulating() {
        MemoryConnection().use { session ->
            session.blockWriter()
            assertTrue(session.connection.send(1, byteArrayOf(1)))
            session.awaitBlocked()
            repeat(15) { assertTrue(session.connection.send(1, byteArrayOf(2))) }
            assertFalse(session.connection.send(1, byteArrayOf(3)))
            assertTrue(session.finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, session.disconnected.get())
        }
    }

    @Test fun closeDropsBothQueuesAndRetainsOnlyInflightAndClosingFrame() {
        val queue = BrowserLanQueue()
        assertTrue(queue.offer(2, byteArrayOf(1), keyFrame = true))
        val active = queue.take()!!
        assertTrue(queue.offer(2, byteArrayOf(2)))
        assertTrue(queue.offer(1, byteArrayOf(3)))
        queue.finishWithClose(byteArrayOf(3, 0xe8.toByte()))
        queue.complete(active)
        val closing = queue.take()!!
        assertEquals(8, closing.opcode)
        queue.complete(closing)
        assertNull(queue.take())
        assertFalse(queue.offer(2, byteArrayOf(4), keyFrame = true))
        assertFalse(queue.offer(1, byteArrayOf(5)))
    }

    @Test fun audioPressureDropsOldestPcmWithoutConsumingVideoOrControlCredits() {
        val queue = BrowserLanQueue()
        val pcm = ByteArray(16_420)
        assertTrue(queue.offerAudio(pcm))
        val active = queue.take()!!
        assertTrue(active.audio)
        repeat(100) { marker ->
            pcm[0] = marker.toByte()
            assertTrue(queue.offerAudio(pcm))
        }
        assertEquals("in-flight PCM must be immutable", 0, active.payload[0].toInt())
        assertTrue(queue.offer(1, byteArrayOf(42)))
        assertTrue(queue.offer(2, byteArrayOf(1), keyFrame = true))
        assertTrue(queue.offer(2, byteArrayOf(2)))
        assertTrue(queue.offer(2, byteArrayOf(3)))
        assertFalse(queue.offer(2, byteArrayOf(4)))
        assertTrue(queue.offer(2, byteArrayOf(5), keyFrame = true))
        queue.complete(active)
        val control = queue.take()!!
        assertEquals(1, control.opcode)
        queue.complete(control)
        val video = queue.take()!!
        assertFalse(video.audio)
        assertEquals(5, video.payload[0].toInt())
        queue.complete(video)
        // With 16,420-byte packets and a 64KiB budget only 3 fit including in-flight.
        val first = queue.take()!!
        assertTrue(first.audio)
        assertEquals(98, first.payload[0].toInt())
        queue.complete(first)
        val second = queue.take()!!
        assertTrue(second.audio)
        assertEquals(99, second.payload[0].toInt())
        queue.complete(second)
        assertFalse(queue.offerAudio(ByteArray(16_421)))
        queue.close()
        assertNull(queue.take())
    }

    @Test fun mediaSchedulingAlternatesAndAudioResetKeepsOnlyInflightPcm() {
        val queue = BrowserLanQueue()
        assertTrue(queue.offerAudio(byteArrayOf(10)))
        assertTrue(queue.offerAudio(byteArrayOf(11)))
        assertTrue(queue.offerAudio(byteArrayOf(12)))
        assertTrue(queue.offer(2, byteArrayOf(1), keyFrame = true))
        assertTrue(queue.offer(2, byteArrayOf(2)))
        for (marker in listOf(10, 1, 11, 2)) {
            val frame = queue.take()!!
            assertEquals(marker, frame.payload[0].toInt())
            queue.complete(frame)
        }
        val active = queue.take()!!
        assertEquals(12, active.payload[0].toInt())
        assertTrue(queue.offerAudio(byteArrayOf(13)))
        queue.resetAudio()
        assertTrue(queue.offer(1, byteArrayOf(42)))
        assertTrue(queue.offer(2, byteArrayOf(3)))
        queue.complete(active)
        val control = queue.take()!!
        assertEquals(42, control.payload[0].toInt())
        queue.complete(control)
        val video = queue.take()!!
        assertEquals(3, video.payload[0].toInt())
        queue.complete(video)
        queue.close()
        assertNull(queue.take())
    }

    @Test fun pcmBurstNeverClosesApprovedSocketAndDeadWriterDeadlineStillApplies() {
        MemoryConnection().use { session ->
            session.blockWriter()
            assertTrue(session.connection.sendAudio(byteArrayOf(3, 1)))
            session.awaitBlocked()
            repeat(1000) { assertTrue(session.connection.sendAudio(byteArrayOf(3, it.toByte()))) }
            assertFalse(session.socket.closed)
            assertEquals(0, session.disconnected.get())
            session.connection.resetAudio()
            assertTrue(session.connection.send(1, byteArrayOf(42)))
            val writeStart = session.clock("writingSince")
            session.connection.checkDeadline(writeStart + 4_999)
            assertFalse(session.socket.closed)
            session.connection.checkDeadline(writeStart + 5_000)
            assertTrue(session.finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, session.disconnected.get())
        }
    }

    @Test fun delayedAudioWorkerIsBoundToItsOriginalApprovedSocket() {
        val server = BrowserLanServer(InetAddress.getByName("192.168.40.2"), ORIGIN, {}, {}, {}, {}, {})
        val owner = BrowserLanServer::class.java.getDeclaredField("owner").apply { isAccessible = true }
        MemoryConnection().use { old ->
            owner.set(server, old.connection)
            val bound = server.bindAudioTransport()!!
            old.connection.stop()
            assertTrue(old.finished.await(2, TimeUnit.SECONDS))
            MemoryConnection().use { replacement ->
                owner.set(server, replacement.connection)
                assertFalse(bound.sendAudio(byteArrayOf(3, 1)))
                assertFalse(bound.sendText("old-audio-state"))
                bound.resetAudio()
                replacement.assertNoMoreFrames()
                assertTrue(server.sendAudio(byteArrayOf(3, 2)))
                assertArrayEquals(byteArrayOf(3, 2), replacement.nextFrame().payload)
            }
        }
    }

    private class MemoryConnection : AutoCloseable {
        val socket = MemorySocket()
        val finished = CountDownLatch(1)
        val disconnected = AtomicInteger()
        val textReceived = CountDownLatch(1)
        private val authenticated = CountDownLatch(1)
        val connection = BrowserLanConnection(socket, HOST, ORIGIN, { it.approve() }, {},
            { authenticated.countDown() }, { textReceived.countDown() },
            { disconnected.incrementAndGet() }, { finished.countDown() })

        init {
            socket.receive(("GET /carplay HTTP/1.1\r\nHost: $HOST\r\nOrigin: $ORIGIN\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Version: 13\r\n" +
                "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n").toByteArray() +
                masked(1, "{\"type\":\"requestApproval\",\"version\":2}".toByteArray()))
            connection.start()
            assertTrue(authenticated.await(2, TimeUnit.SECONDS))
            val upgrade = socket.writes.poll(2, TimeUnit.SECONDS)
            assertNotNull(upgrade)
            assertTrue(String(upgrade!!).startsWith("HTTP/1.1 101 "))
            assertEquals("{\"type\":\"approvalPending\",\"version\":2}", String(nextFrame().payload))
        }

        fun blockWriter() { socket.holdWrites = true }
        fun awaitBlocked() { assertTrue(socket.blocked.await(2, TimeUnit.SECONDS)) }
        fun releaseWriter() { socket.holdWrites = false; socket.release.countDown() }
        fun sendVideo(marker: Int, key: Boolean = false) = connection.send(2, ByteArray(1024) { marker.toByte() }, keyFrame = key)
        fun now() = System.nanoTime() / 1_000_000
        fun clock(name: String): Long = BrowserLanConnection::class.java.getDeclaredField(name).let {
            it.isAccessible = true; it.getLong(connection)
        }
        fun setClock(name: String, value: Long) = BrowserLanConnection::class.java.getDeclaredField(name).let {
            it.isAccessible = true; it.setLong(connection, value)
        }
        fun nextFrame(): BrowserLanProtocol.Frame {
            val bytes = socket.writes.poll(2, TimeUnit.SECONDS)
            assertNotNull("Writer did not flush the expected frame", bytes)
            val input = ByteArrayInputStream(bytes!!)
            val first = input.read()
            assertEquals(0x80, first and 0xf0)
            var size = input.read()
            assertTrue(size in 0..127)
            if (size == 126) size = (input.read() shl 8) or input.read()
            else if (size == 127) {
                var longSize = 0L
                repeat(8) { longSize = (longSize shl 8) or input.read().toLong() }
                size = longSize.toInt()
            }
            assertEquals(size, input.available())
            return BrowserLanProtocol.Frame(first and 15, input.readBytes())
        }
        fun assertNoMoreFrames() { assertNull(socket.writes.poll(100, TimeUnit.MILLISECONDS)) }
        override fun close() {
            connection.stop()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
        }
    }

    /** Blocks real production connection writes deterministically without TCP buffers/timing. */
    private class MemorySocket : Socket() {
        private val inputLock = Object()
        private val incoming = ArrayDeque<Byte>()
        @Volatile var closed = false
        @Volatile var holdWrites = false
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val writes = LinkedBlockingQueue<ByteArray>()
        private val input = object : InputStream() {
            override fun read(): Int = synchronized(inputLock) {
                while (incoming.isEmpty() && !closed) inputLock.wait()
                if (incoming.isEmpty()) -1 else incoming.removeFirst().toInt() and 255
            }
            override fun read(bytes: ByteArray, offset: Int, size: Int): Int = synchronized(inputLock) {
                if (size == 0) return@synchronized 0
                while (incoming.isEmpty() && !closed) inputLock.wait()
                if (incoming.isEmpty()) return@synchronized -1
                val count = minOf(size, incoming.size)
                repeat(count) { bytes[offset + it] = incoming.removeFirst() }
                count
            }
        }
        private val output = object : OutputStream() {
            private val bytes = ByteArrayOutputStream()
            override fun write(value: Int) {
                if (holdWrites) {
                    blocked.countDown()
                    if (!release.await(3, TimeUnit.SECONDS)) throw IOException("Test writer was never released")
                }
                if (closed) throw IOException("Socket closed")
                bytes.write(value)
            }
            override fun flush() { writes.add(bytes.toByteArray()); bytes.reset() }
        }
        fun receive(bytes: ByteArray) = synchronized(inputLock) {
            bytes.forEach(incoming::addLast)
            inputLock.notifyAll()
        }
        override fun getInputStream() = input
        override fun getOutputStream() = output
        override fun setTcpNoDelay(value: Boolean) {}
        override fun setSoTimeout(value: Int) {}
        override fun setSendBufferSize(value: Int) {}
        override fun close() {
            closed = true
            release.countDown()
            synchronized(inputLock) { inputLock.notifyAll() }
        }
    }

    companion object {
        private fun outputField(name: String) = BrowserOutput::class.java.getDeclaredField(name).apply { isAccessible = true }
        private const val HOST = "192.168.40.2:41234"
        private const val ORIGIN = BrowserOutput.VIEWER_ORIGIN
        private const val TOKEN = "synthetic-token-0123456789"
        private fun masked(opcode: Int, payload: ByteArray): ByteArray {
            require(payload.size < 126)
            return ByteArrayOutputStream().apply {
                write(0x80 or opcode)
                write(0x80 or payload.size)
                write(byteArrayOf(1, 2, 3, 4))
                payload.forEachIndexed { index, value -> write(value.toInt() xor ((index and 3) + 1)) }
            }.toByteArray()
        }
    }
}
