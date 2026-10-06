package com.shilapi.xcertplay.browser

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A deliberately small, single-viewer LAN WebSocket endpoint. No HTTP files, discovery,
 * URL credentials, TLS fallback, compression, binary input or fragmented messages.
 *
 * The caller must provide a fresh random, URL-safe token and an exact trusted HTTPS
 * origin. This is plaintext LAN transport: pairing does not protect against a hostile
 * LAN observer. A browser must independently permit HTTPS -> private-address ws://.
 *
 * Callbacks run serially on the reader thread and must not block. onDisconnected is
 * called once for an authenticated session, including close()/timeouts. Sends copy
 * their payload and never do socket writes. Video pressure drops queued dependencies
 * until a fresh keyframe; a persistently blocked writer still closes on its deadline.
 * Create a new server (and token) after close().
 */
class BrowserLanServer(
    private val bindAddress: InetAddress,
    private val allowedOrigin: String,
    token: String,
    private val onAuthenticated: () -> Unit,
    private val onText: (String) -> Unit,
    private val onDisconnected: () -> Unit,
) : AutoCloseable {
    private val tokenBytes = token.toByteArray(Charsets.US_ASCII)
    private val lock = Any()
    private var listener: ServerSocket? = null
    private var closed = false
    @Volatile private var owner: BrowserLanConnection? = null
    private var watchdog: java.util.concurrent.ScheduledExecutorService? = null

    init {
        require(BrowserLanProtocol.isPrivateIpv4(bindAddress)) { "A private LAN IPv4 address is required" }
        require(BrowserLanProtocol.isHttpsOrigin(allowedOrigin)) { "An exact HTTPS origin is required" }
        require(token.matches(Regex("[A-Za-z0-9_-]{20,128}"))) { "A random URL-safe pairing token is required" }
    }

    /** Binds only an assigned, up, non-loopback RFC1918 IPv4 interface. */
    fun start(): Int = synchronized(lock) {
        check(!closed) { "Server is closed" }
        listener?.let { return@synchronized it.localPort }
        val network = NetworkInterface.getByInetAddress(bindAddress)
        require(network != null && network.isUp && !network.isLoopback) { "The selected LAN interface is unavailable" }
        val server = ServerSocket()
        try {
            server.reuseAddress = false
            server.bind(InetSocketAddress(bindAddress, 0), 2)
            listener = server
            watchdog = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "CarPlay-LAN-deadlines").apply { isDaemon = true }
            }.also { it.scheduleAtFixedRate({ owner?.checkDeadline() }, 250, 250, TimeUnit.MILLISECONDS) }
            Thread({ acceptLoop(server) }, "CarPlay-LAN-accept").apply { isDaemon = true; start() }
            server.localPort
        } catch (failure: Exception) {
            listener = null
            watchdog?.shutdownNow()
            watchdog = null
            runCatching { server.close() }
            throw failure
        }
    }

    fun sendText(text: String, resetVideo: Boolean = false): Boolean {
        if (text.length > BrowserLanProtocol.MAX_TEXT_BYTES) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > BrowserLanProtocol.MAX_TEXT_BYTES) return false
        return owner?.send(1, bytes, resetVideo = resetVideo) ?: false
    }

    fun sendBinary(bytes: ByteArray, keyFrame: Boolean): Boolean {
        if (bytes.size > BrowserLanProtocol.MAX_BINARY_BYTES) return false
        return owner?.send(2, bytes, keyFrame = keyFrame) ?: false
    }

    override fun close() {
        val session: BrowserLanConnection?
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { listener?.close() }
            listener = null
            watchdog?.shutdownNow()
            watchdog = null
            tokenBytes.fill(0)
            session = owner
        }
        session?.stop()
    }

    private fun acceptLoop(server: ServerSocket) {
        // Bounds thread creation/auth attempts even if malformed handshakes disconnect quickly.
        val attempts = BrowserLanRateLimit(8, 10_000)
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: IOException) { return }
            synchronized(lock) {
                if (closed || owner != null || !attempts.allow() ||
                    !BrowserLanProtocol.isPrivateIpv4(socket.inetAddress)
                ) {
                    runCatching { socket.close() }
                } else {
                    val session = BrowserLanConnection(
                        socket, "${bindAddress.hostAddress}:${server.localPort}", allowedOrigin,
                        tokenBytes.copyOf(), onAuthenticated, onText, onDisconnected,
                    ) { finished -> synchronized(lock) { if (owner === finished) owner = null } }
                    owner = session
                    session.start()
                }
            }
        }
    }
}

/** Internal connection seam lets JVM tests use loopback without relaxing the public LAN bind policy. */
internal class BrowserLanConnection(
    private val socket: Socket,
    private val expectedHost: String,
    private val origin: String,
    private val token: ByteArray,
    private val onAuthenticated: () -> Unit,
    private val onText: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onFinished: (BrowserLanConnection) -> Unit,
) {
    private val stopped = AtomicBoolean(false)
    private val outbound = BrowserLanQueue()
    private val acceptedAt = monotonicMillis()
    @Volatile private var upgraded = false
    @Volatile private var authenticated = false
    @Volatile private var writingSince = 0L
    @Volatile private var lastRead = acceptedAt
    @Volatile private var lastPing = acceptedAt
    private var writer: Thread? = null

    fun start() {
        Thread(::readLoop, "CarPlay-LAN-reader").apply { isDaemon = true; start() }
    }

    fun send(opcode: Int, bytes: ByteArray, keyFrame: Boolean = false, resetVideo: Boolean = false): Boolean {
        if (!authenticated || stopped.get()) return false
        if (outbound.offer(opcode, bytes, keyFrame, resetVideo)) return true
        // A missed video dependency needs a fresh keyframe, not a new WebSocket.
        if (opcode != 2) stop()
        return false
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        outbound.close()
        runCatching { socket.close() }
    }

    fun checkDeadline(now: Long = monotonicMillis()) {
        if (stopped.get()) return
        val writeStart = writingSince
        if ((!upgraded && now - acceptedAt >= 5_000) ||
            (!authenticated && now - acceptedAt >= 10_000) ||
            (authenticated && now - lastRead >= 30_000) ||
            (writeStart != 0L && now - writeStart >= 5_000)
        ) {
            stop()
        } else if (authenticated && now - lastPing >= 10_000) {
            lastPing = now
            if (!outbound.offer(9, byteArrayOf())) stop()
        }
    }

    private fun readLoop() {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000 // Watchdog also enforces absolute slowloris/write deadlines.
            socket.sendBufferSize = 64 * 1024
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val output = socket.getOutputStream()
            val accept = BrowserLanProtocol.handshake(input, expectedHost, origin)
            writingSince = monotonicMillis()
            output.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            writingSince = 0
            upgraded = true
            writer = Thread({ writeLoop(output) }, "CarPlay-LAN-writer").apply { isDaemon = true; start() }
            val auth = BrowserLanProtocol.readFrame(input)
            if (auth.opcode != 1 || !BrowserLanProtocol.authenticate(auth.payload, token)) {
                throw BrowserLanProtocol.Failure(1008)
            }
            if (stopped.get()) return
            authenticated = true
            token.fill(0)
            lastRead = monotonicMillis()
            socket.soTimeout = 30_000
            onAuthenticated()
            val messages = BrowserLanRateLimit(240, 1_000)
            while (!stopped.get()) {
                val frame = BrowserLanProtocol.readFrame(input)
                lastRead = monotonicMillis()
                if (!messages.allow()) throw BrowserLanProtocol.Failure(1008)
                when (frame.opcode) {
                    1 -> onText(BrowserLanProtocol.utf8(frame.payload))
                    8 -> {
                        BrowserLanProtocol.validateClose(frame.payload)
                        gracefulClose(frame.payload)
                        return
                    }
                    9 -> if (!outbound.offer(10, frame.payload)) return
                    10 -> Unit
                    else -> throw BrowserLanProtocol.Failure(1003)
                }
            }
        } catch (failure: BrowserLanProtocol.Failure) {
            if (upgraded) gracefulClose(byteArrayOf((failure.closeCode ushr 8).toByte(), failure.closeCode.toByte()))
        } catch (_: Exception) {
            // Never log authentication tokens, request contents, touch input or video.
        } finally {
            stop()
            token.fill(0)
            try { if (authenticated) onDisconnected() } finally { onFinished(this) }
        }
    }

    private fun gracefulClose(payload: ByteArray) {
        outbound.finishWithClose(payload)
        // Give the bounded writer a brief opportunity to send the closing frame.
        try { writer?.join(200) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun writeLoop(output: OutputStream) {
        try {
            while (!stopped.get()) {
                val frame = outbound.take() ?: break
                writingSince = monotonicMillis()
                BrowserLanProtocol.writeFrame(output, frame)
                output.flush()
                writingSince = 0
                outbound.complete(frame)
                if (frame.opcode == 8) break
            }
        } catch (_: Exception) {
            // Closing the socket wakes both the reader and a blocked writer.
        } finally {
            stop()
        }
    }
}

internal object BrowserLanProtocol {
    const val MAX_TEXT_BYTES = 8192
    const val MAX_BINARY_BYTES = 4 * 1024 * 1024
    private const val MAX_HEADERS = 8192
    class Failure(val closeCode: Int = 1002) : IOException("Invalid browser bridge protocol")
    data class Frame(val opcode: Int, val payload: ByteArray)

    fun isPrivateIpv4(address: InetAddress): Boolean {
        if (address !is Inet4Address) return false
        val bytes = address.address
        val first = bytes[0].toInt() and 255
        val second = bytes[1].toInt() and 255
        return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
    }

    fun isHttpsOrigin(origin: String): Boolean = try {
        val uri = URI(origin)
        origin.length <= 512 && uri.scheme == "https" && !uri.host.isNullOrEmpty() &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
            uri.rawPath.isNullOrEmpty() && uri.port in -1..65535 && uri.port != 0 &&
            origin == "https://${uri.rawAuthority}" && origin.all { it.code in 33..126 } &&
            '*' !in origin
    } catch (_: Exception) { false }

    fun handshake(input: InputStream, expectedHost: String, origin: String): String {
        val bytes = ByteArray(MAX_HEADERS)
        var size = 0
        while (size < bytes.size) {
            val value = input.read()
            if (value < 0) throw EOFException()
            if (value != 9 && value != 10 && value != 13 && value !in 32..126) throw Failure()
            bytes[size++] = value.toByte()
            if (size >= 4 && bytes[size - 4] == 13.toByte() && bytes[size - 3] == 10.toByte() &&
                bytes[size - 2] == 13.toByte() && bytes[size - 1] == 10.toByte()
            ) break
        }
        if (size < 4 || bytes[size - 4] != 13.toByte() || bytes[size - 3] != 10.toByte() ||
            bytes[size - 2] != 13.toByte() || bytes[size - 1] != 10.toByte()
        ) throw Failure()
        val lines = String(bytes, 0, size - 4, Charsets.US_ASCII).split("\r\n")
        if (lines.firstOrNull() != "GET /carplay HTTP/1.1" || lines.size > 65) throw Failure()
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw Failure()
            val name = line.substring(0, colon)
            if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) throw Failure()
            val value = line.substring(colon + 1).trim(' ', '\t')
            if (value.any { it == '\r' || it == '\n' }) throw Failure()
            if (headers.put(name.lowercase(Locale.ROOT), value) != null) throw Failure()
        }
        if (headers["host"] != expectedHost || headers["origin"] != origin ||
            !headers["upgrade"].equals("websocket", ignoreCase = true) ||
            headers["connection"]?.split(',')?.none { it.trim().equals("upgrade", ignoreCase = true) } != false ||
            headers["sec-websocket-version"] != "13" || "transfer-encoding" in headers ||
            (headers["content-length"] != null && headers["content-length"] != "0")
        ) throw Failure()
        val key = headers["sec-websocket-key"] ?: throw Failure()
        val decoded = try { Base64.getDecoder().decode(key) } catch (_: IllegalArgumentException) { throw Failure() }
        if (decoded.size != 16 || Base64.getEncoder().encodeToString(decoded) != key) throw Failure()
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII)))
    }

    fun readFrame(input: InputStream): Frame {
        val first = byte(input)
        val second = byte(input)
        val opcode = first and 15
        // Deliberately accepts only complete messages; no extensions are negotiated.
        if (first and 0x80 == 0 || first and 0x70 != 0 || second and 0x80 == 0 ||
            opcode !in intArrayOf(1, 2, 8, 9, 10)
        ) throw Failure()
        val shortLength = second and 127
        var length = shortLength.toLong()
        if (shortLength == 126) {
            length = ((byte(input) shl 8) or byte(input)).toLong()
            if (length < 126) throw Failure()
        } else if (shortLength == 127) {
            length = 0
            repeat(8) { index ->
                val next = byte(input)
                if (index == 0 && next and 128 != 0) throw Failure()
                length = (length shl 8) or next.toLong()
            }
            if (length < 65536) throw Failure()
        }
        if (opcode >= 8 && length > 125) throw Failure()
        if (length > MAX_TEXT_BYTES) throw Failure(1009)
        if (opcode == 2) throw Failure(1003)
        val mask = ByteArray(4)
        readFully(input, mask)
        val payload = ByteArray(length.toInt())
        readFully(input, payload)
        for (index in payload.indices) payload[index] = (payload[index].toInt() xor mask[index and 3].toInt()).toByte()
        if (opcode == 1) utf8(payload)
        return Frame(opcode, payload)
    }

    fun writeFrame(output: OutputStream, frame: Frame) {
        output.write(0x80 or frame.opcode)
        when {
            frame.payload.size < 126 -> output.write(frame.payload.size)
            frame.payload.size <= 65535 -> {
                output.write(126)
                output.write(frame.payload.size ushr 8)
                output.write(frame.payload.size)
            }
            else -> {
                output.write(127)
                for (shift in 56 downTo 0 step 8) output.write((frame.payload.size.toLong() ushr shift).toInt() and 255)
            }
        }
        output.write(frame.payload)
    }

    fun utf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) { throw Failure(1007) }

    fun validateClose(payload: ByteArray) {
        if (payload.size == 1) throw Failure()
        if (payload.size < 2) return
        val code = ((payload[0].toInt() and 255) shl 8) or (payload[1].toInt() and 255)
        if (code !in 1000..1014 && code !in 3000..4999 || code in intArrayOf(1004, 1005, 1006)) throw Failure()
        utf8(payload.copyOfRange(2, payload.size))
    }

    fun authenticate(payload: ByteArray, expected: ByteArray): Boolean {
        val fields = AuthObject(utf8(payload)).parse() ?: return false
        if (fields["type"] != "auth" || fields.size != 2) return false
        val supplied = fields["token"]?.toByteArray(Charsets.UTF_8) ?: return false
        // Fixed work for the configured token length. No string equality/early exit on token bytes.
        var difference = expected.size xor supplied.size
        for (index in expected.indices) {
            difference = difference or (expected[index].toInt() xor (supplied.getOrNull(index)?.toInt() ?: 0))
        }
        supplied.fill(0)
        return difference == 0
    }

    private fun byte(input: InputStream): Int = input.read().also { if (it < 0) throw EOFException() }
    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) throw EOFException()
            if (count == 0) continue
            offset += count
        }
    }

    /** Strict, shallow JSON object; only two bounded string fields can be allocated. */
    private class AuthObject(private val input: String) {
        private var position = 0
        fun parse(): Map<String, String>? = try {
            val result = mutableMapOf<String, String>()
            expect('{')
            repeat(2) { index ->
                if (index != 0) expect(',')
                val key = string(16)
                if (key != "type" && key != "token" || key in result) throw Failure()
                expect(':')
                result[key] = string(128)
            }
            expect('}')
            whitespace()
            if (position != input.length) throw Failure()
            result
        } catch (_: Failure) { null }

        private fun whitespace() { while (position < input.length && input[position] in " \t\r\n") position++ }
        private fun expect(character: Char) {
            whitespace()
            if (position >= input.length || input[position++] != character) throw Failure()
        }
        private fun string(limit: Int): String {
            expect('"')
            val value = StringBuilder()
            while (position < input.length) {
                var character = input[position++]
                if (character == '"') return value.toString()
                if (character.code < 32) throw Failure()
                if (character == '\\') {
                    if (position >= input.length) throw Failure()
                    character = when (val escape = input[position++]) {
                        '"', '\\', '/' -> escape
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            if (position + 4 > input.length) throw Failure()
                            val code = input.substring(position, position + 4).toIntOrNull(16) ?: throw Failure()
                            position += 4
                            code.toChar()
                        }
                        else -> throw Failure()
                    }
                }
                if (character.isSurrogate()) throw Failure()
                value.append(character)
                if (value.length > limit) throw Failure()
            }
            throw Failure()
        }
    }
}

/**
 * Independent bounded credits include the writer's in-flight frame. Control is FIFO and
 * takes precedence over pending video; a config/status boundary must set resetVideo so
 * older queued video cannot follow it. Keyframe metadata comes from the media producer,
 * not from inspecting/rewriting the opaque binary packet here.
 */
internal class BrowserLanQueue {
    private val lock = Object()
    private val video = ArrayDeque<BrowserLanProtocol.Frame>()
    private val control = ArrayDeque<BrowserLanProtocol.Frame>()
    private var videoCount = 0
    private var videoBytes = 0L
    private var controlCount = 0
    private var controlBytes = 0L
    private var waitingForKey = true
    private var closed = false

    fun offer(opcode: Int, payload: ByteArray, keyFrame: Boolean = false, resetVideo: Boolean = false): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        if (opcode == 2) {
            if (payload.size > BrowserLanProtocol.MAX_BINARY_BYTES || videoCount >= MAX_VIDEO_FRAMES ||
                videoBytes + payload.size > MAX_VIDEO_BYTES
            ) {
                discardVideo()
                waitingForKey = true
            }
            if (payload.size > BrowserLanProtocol.MAX_BINARY_BYTES || (waitingForKey && !keyFrame)) {
                return@synchronized false
            }
            // After discarding, at most one <=4 MiB frame remains in flight; a fresh
            // keyframe always fits without borrowing the control queue's credits.
            video.addLast(BrowserLanProtocol.Frame(opcode, payload.copyOf()))
            videoCount++
            videoBytes += payload.size
            waitingForKey = false
        } else {
            val limit = if (opcode >= 8) 125 else BrowserLanProtocol.MAX_TEXT_BYTES
            if (payload.size > limit || controlCount >= MAX_CONTROL_FRAMES ||
                controlBytes + payload.size > MAX_CONTROL_BYTES
            ) return@synchronized false
            if (resetVideo) {
                discardVideo()
                waitingForKey = true
            }
            control.addLast(BrowserLanProtocol.Frame(opcode, payload.copyOf()))
            controlCount++
            controlBytes += payload.size
        }
        lock.notifyAll()
        true
    }

    fun take(): BrowserLanProtocol.Frame? = synchronized(lock) {
        while (control.isEmpty() && video.isEmpty() && !closed) lock.wait()
        when {
            control.isNotEmpty() -> control.removeFirst()
            video.isNotEmpty() -> video.removeFirst()
            else -> null
        }
    }

    fun complete(frame: BrowserLanProtocol.Frame) = synchronized(lock) {
        if (frame.opcode == 2) {
            videoCount--
            videoBytes -= frame.payload.size
        } else {
            controlCount--
            controlBytes -= frame.payload.size
        }
    }

    fun finishWithClose(payload: ByteArray) = synchronized(lock) {
        if (closed) return@synchronized
        discardVideo()
        discardControl()
        control.addLast(BrowserLanProtocol.Frame(8, payload.copyOf()))
        controlCount++
        controlBytes += payload.size
        closed = true
        lock.notifyAll()
    }

    fun close() = synchronized(lock) {
        closed = true
        discardVideo()
        discardControl()
        lock.notifyAll()
    }

    private fun discardVideo() {
        while (video.isNotEmpty()) complete(video.removeFirst())
    }

    private fun discardControl() {
        while (control.isNotEmpty()) complete(control.removeFirst())
    }

    companion object {
        private const val MAX_VIDEO_FRAMES = 3
        private const val MAX_VIDEO_BYTES = 8L * 1024 * 1024
        private const val MAX_CONTROL_FRAMES = 16
        private const val MAX_CONTROL_BYTES = 64L * 1024
    }
}

internal class BrowserLanRateLimit(private val maximum: Int, private val intervalMillis: Long) {
    private var window = monotonicMillis()
    private var count = 0
    fun allow(now: Long = monotonicMillis()): Boolean {
        if (now - window >= intervalMillis) { window = now; count = 0 }
        return ++count <= maximum
    }
}

private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000
