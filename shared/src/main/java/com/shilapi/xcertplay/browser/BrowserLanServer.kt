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
import java.net.SocketTimeoutException
import java.io.SequenceInputStream
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong
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
 * A deliberately small, single-viewer LAN WebSocket endpoint with a generic /health probe.
 * Bundled HTTP files are served only by the fixed-origin TLS variant. No discovery,
 * URL credentials, TLS fallback, compression, binary input or fragmented messages.
 *
 * Each connection requires a foreground Android permission decision and the exact trusted HTTPS
 * origin. This is plaintext LAN transport: pairing does not protect against a hostile
 * LAN observer. A browser must independently permit HTTPS -> private-address ws://.
 *
 * Callbacks run serially on the reader thread and must not block. onDisconnected is
 * called once for an authenticated session, including close()/timeouts. Sends copy
 * their payload and never do socket writes. Video pressure drops queued dependencies
 * until a fresh keyframe; a persistently blocked writer still closes on its deadline.
 * Create a new server after close().
 */
class BrowserLanServer(
    private val bindAddress: InetAddress,
    private val allowedOrigin: String,
    private val onApprovalRequested: (BrowserApprovalRequest) -> Unit,
    private val onApprovalFinished: (Long) -> Unit,
    private val onAuthenticated: () -> Unit,
    private val onText: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val secureIdentity: BrowserTlsIdentity? = null,
    private val viewerAssets: BrowserViewerAssets? = null,
) : AutoCloseable {
    private val lock = Any()
    private var listener: ServerSocket? = null
    private var closed = false
    @Volatile private var owner: BrowserLanConnection? = null
    private var watchdog: java.util.concurrent.ScheduledExecutorService? = null
    private val preflights = mutableSetOf<BrowserLanConnection>()
    private val attempts = BrowserLanRateLimit(if (secureIdentity == null) 8 else 64, 10_000)
    private val diagnostics = BrowserConnectionDiagnostics()

    /** Local-only snapshots; /health never exposes these records. */
    fun diagnosticsSnapshot(): List<BrowserConnectionEvent> = diagnostics.snapshot()
    fun diagnosticsReport(): String = diagnostics.report()

    init {
        if (secureIdentity == null) {
            require(viewerAssets == null) { "Assets require TLS" }
            require(BrowserLanProtocol.isPrivateIpv4(bindAddress)) { "A private LAN IPv4 address is required" }
            require(allowedOrigin == "https://mark4z.github.io") { "The trusted viewer origin is required" }
        } else {
            require(bindAddress.hostAddress == BrowserViewerAssets.ADDRESS) { "The exact local VPN address is required" }
            require(allowedOrigin == BrowserViewerAssets.ORIGIN && viewerAssets != null) { "The bundled HTTPS origin is required" }
            secureIdentity.checkValidity(BrowserViewerAssets.HOSTNAME)
        }
    }

    /** Plain LAN requires an UP RFC1918 interface. TLS pins the retained dual-TUN address. */
    fun start(): Int = synchronized(lock) {
        check(!closed) { "Server is closed" }
        listener?.let { return@synchronized it.localPort }
        val network = NetworkInterface.getByInetAddress(bindAddress)
        require(network != null && (secureIdentity != null || network.isUp) && !network.isLoopback) { "The selected LAN interface is unavailable" }
        // Own the raw TCP listener/socket independently from TLS. Cancellation must
        // interrupt a blocked TLS writer even if its provider drains close_notify.
        val server = bindBrowserListener(bindAddress,
            if (secureIdentity == null) 0 else BrowserViewerAssets.PORT,
            reuseAddress = secureIdentity != null)
        try {
            listener = server
            watchdog = Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "CarPlay-LAN-deadlines").apply { isDaemon = true }
            }.also { it.scheduleAtFixedRate({ checkDeadlines() }, 250, 250, TimeUnit.MILLISECONDS) }
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
        val sessions: List<BrowserLanConnection>
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { listener?.close() }
            listener = null
            watchdog?.shutdownNow()
            watchdog = null
            sessions = preflights.toList() + listOfNotNull(owner)
        }
        sessions.forEach { it.stop() }
    }

    internal fun checkDeadlines(now: Long = monotonicMillis()) {
        val sessions = synchronized(lock) { preflights.toList() + listOfNotNull(owner) }
        sessions.forEach { it.checkDeadline(now) }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (_: IOException) { return }
            acceptConnection(socket, if (secureIdentity == null) "${bindAddress.hostAddress}:${server.localPort}" else BrowserViewerAssets.AUTHORITY)
        }
    }

    /** Internal socket seam for loopback JVM tests; the public listener still enforces RFC1918. */
    internal fun acceptConnection(socket: Socket, expectedHost: String, privatePeer: Boolean =
        BrowserLanProtocol.isPrivateIpv4(socket.inetAddress) ||
            (secureIdentity != null && socket.inetAddress.hostAddress?.let {
                it == BrowserViewerAssets.ADDRESS || it == BrowserViewerAssets.COMPAT_ADDRESS
            } == true)) {
        synchronized(lock) {
            val attempt = diagnostics.begin()
            val rejection = when {
                closed -> BrowserConnectionReason.SERVER_STOPPED
                !privatePeer -> BrowserConnectionReason.NON_PRIVATE_PEER
                !attempts.allow() -> BrowserConnectionReason.RATE_LIMIT
                preflights.size >= (if (secureIdentity == null) 4 else 16) -> BrowserConnectionReason.PREFLIGHT_LIMIT
                else -> null
            }
            if (rejection != null) {
                attempt.record(BrowserConnectionStage.CLOSED, rejection)
                runCatching { socket.close() }
                return
            }
            // Health probes have their own bounded short-lived slots. Only a fully
            // validated upgrade may claim the single viewer slot, under the same lock.
            val session = BrowserLanConnection(
                socket, expectedHost, allowedOrigin,
                onApprovalRequested, onApprovalFinished, onAuthenticated, onText, onDisconnected,
                onFinished = { finished -> synchronized(lock) {
                    preflights.remove(finished)
                    if (owner === finished) owner = null
                } },
                diagnostic = attempt,
                viewerAssets = viewerAssets,
                secureIdentity = secureIdentity,
                claimViewer = { candidate -> synchronized(lock) {
                    if (closed || owner != null || !preflights.remove(candidate)) false
                    else { owner = candidate; true }
                } },
            )
            preflights.add(session)
            session.start()
        }
    }

}

/** The fixed HTTPS endpoint must reopen after server-closed health/asset connections.
 * Set SO_REUSEADDR before bind so their TIME_WAIT sockets do not reserve the endpoint.
 * This is not SO_REUSEPORT: a second live listener on the same address still fails.
 * Keep the selected address and port exact; never fall back to wildcard or another port.
 * Internal seam permits real loopback restart tests without relaxing public bind policy. */
internal fun bindBrowserListener(address: InetAddress, port: Int, reuseAddress: Boolean): ServerSocket {
    val server = ServerSocket()
    try {
        server.reuseAddress = reuseAddress
        server.bind(InetSocketAddress(address, port), 16)
        return server
    } catch (failure: Exception) {
        runCatching { server.close() }
        throw failure
    }
}

/** A one-shot grant for one live connection. No device identity or credential is persisted. */
class BrowserApprovalRequest internal constructor(
    val id: Long,
    val remoteAddress: String,
    private val expiresAt: Long,
    private val isActive: () -> Boolean = { true },
) {
    private var decision = 0 // pending, approved, rejected, consumed/closed
    private var promptReported = false
    private var promptObserver: ((Boolean) -> Unit)? = null
    private var automaticObserver: (() -> Unit)? = null
    @Synchronized internal fun observeAutomaticApproval(observer: () -> Unit) { automaticObserver = observer }
    @Synchronized fun approveAutomatically(): Boolean {
        if (!approve()) return false
        runCatching { automaticObserver?.invoke() }
        return true
    }
    @Synchronized internal fun observePrompt(observer: (Boolean) -> Unit) { promptObserver = observer }
    fun markPromptShown() { reportPrompt(true) }
    fun markPromptUnavailable() { reportPrompt(false) }
    @Synchronized private fun reportPrompt(shown: Boolean) {
        if (promptReported || decision != 0) return
        promptReported = true
        // Diagnostics must never change or interrupt the approval decision.
        runCatching { promptObserver?.invoke(shown) }
    }
    @Synchronized fun approve(): Boolean {
        if (decision != 0 || !isActive() || monotonicMillis() >= expiresAt) return false
        decision = 1
        return true
    }
    @Synchronized fun reject() { if (decision in 0..1) decision = 2 }
    @Synchronized internal fun takeDecision(): Int {
        if (monotonicMillis() >= expiresAt && decision in 0..1) decision = 2
        val result = decision
        if (result != 0) decision = 3
        return result
    }
    @Synchronized internal fun invalidate() { decision = 3 }
}

/** Internal connection seam lets JVM tests use loopback without relaxing the public LAN bind policy. */
internal class BrowserLanConnection(
    private val socket: Socket,
    private val expectedHost: String,
    private val origin: String,
    private val onApprovalRequested: (BrowserApprovalRequest) -> Unit,
    private val onApprovalFinished: (Long) -> Unit,
    private val onAuthenticated: () -> Unit,
    private val onText: (String) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onFinished: (BrowserLanConnection) -> Unit,
    private val diagnostic: BrowserConnectionDiagnostics.Attempt?,
    private val claimViewer: (BrowserLanConnection) -> Boolean,
    private val viewerAssets: BrowserViewerAssets? = null,
    private val secureIdentity: BrowserTlsIdentity? = null,
) {
    // Keep the original positional and trailing-lambda constructor used by JVM seams.
    constructor(socket: Socket, expectedHost: String, origin: String,
        onApprovalRequested: (BrowserApprovalRequest) -> Unit, onApprovalFinished: (Long) -> Unit,
        onAuthenticated: () -> Unit, onText: (String) -> Unit, onDisconnected: () -> Unit,
        onFinished: (BrowserLanConnection) -> Unit,
    ) : this(socket, expectedHost, origin, onApprovalRequested, onApprovalFinished,
        onAuthenticated, onText, onDisconnected, onFinished, null, { true })

    @Volatile private var tlsSocket: javax.net.ssl.SSLSocket? = null
    private val stopped = AtomicBoolean(false)
    private val approvalLock = Any()
    private val outbound = BrowserLanQueue()
    private val acceptedAt = monotonicMillis()
    @Volatile private var upgraded = false
    @Volatile private var authenticated = false
    @Volatile private var writingSince = 0L
    @Volatile private var lastRead = acceptedAt
    @Volatile private var lastPing = acceptedAt
    private var writer: Thread? = null
    @Volatile private var approval: BrowserApprovalRequest? = null
    @Volatile private var approvalStartedAt = 0L

    companion object { private val nextRequestId = AtomicLong() }

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

    fun stop() { stopWithReason(BrowserConnectionReason.NONE) }

    private fun stopWithReason(reason: BrowserConnectionReason, deadline: Boolean = false) {
        synchronized(approvalLock) {
            if (!stopped.compareAndSet(false, true)) return
            approval?.invalidate()
            if (deadline) diagnostic?.record(BrowserConnectionStage.DEADLINE, reason)
            diagnostic?.record(BrowserConnectionStage.CLOSED, reason)
        }
        outbound.close()
        // Hard-close TCP first. TLS close_notify must never hold the raw descriptor
        // or the single watchdog thread hostage behind an outstanding TLS write.
        runCatching { socket.close() }
        runCatching { tlsSocket?.close() }
    }

    fun checkDeadline(now: Long = monotonicMillis()) {
        if (stopped.get()) return
        val writeStart = writingSince
        val reason = when {
            !upgraded && now - acceptedAt >= 5_000 -> BrowserConnectionReason.HEADER_DEADLINE
            !authenticated && approvalStartedAt == 0L && now - acceptedAt >= 10_000 ->
                BrowserConnectionReason.APPROVAL_REQUEST_DEADLINE
            !authenticated && approvalStartedAt != 0L && now - approvalStartedAt >= 30_000 ->
                BrowserConnectionReason.APPROVAL_DEADLINE
            authenticated && now - lastRead >= 30_000 -> BrowserConnectionReason.IDLE_DEADLINE
            writeStart != 0L && now - writeStart >= 5_000 -> BrowserConnectionReason.WRITE_DEADLINE
            else -> null
        }
        if (reason != null) {
            stopWithReason(reason, deadline = true)
        } else if (authenticated && now - lastPing >= 10_000) {
            lastPing = now
            if (!outbound.offer(9, byteArrayOf())) stop()
        }
    }

    private fun readLoop() {
        var websocketRequested = false
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000 // Watchdog also enforces absolute slowloris/write deadlines.
            socket.sendBufferSize = 64 * 1024
            val transportSocket = if (viewerAssets != null) {
                val tls = secureIdentity?.wrapServerSocket(socket) ?: throw IOException("TLS required")
                tlsSocket = tls
                if (stopped.get()) { runCatching { tls.close() }; return }
                tls.soTimeout = 10_000
                val parameters = tls.sslParameters
                parameters.sniMatchers = listOf(javax.net.ssl.SNIHostName.createSNIMatcher("(?i)tesla\\.mark4z\\.asia"))
                tls.sslParameters = parameters
                diagnostic?.record(BrowserConnectionStage.TLS_HANDSHAKE_STARTED)
                tls.startHandshake()
                val names = (tls.session as? javax.net.ssl.ExtendedSSLSession)?.requestedServerNames
                if (names == null || names.size != 1 ||
                    (names.single() as? javax.net.ssl.SNIHostName)?.asciiName?.lowercase(Locale.ROOT) != BrowserViewerAssets.HOSTNAME) {
                    throw IOException("Expected TLS server name required")
                }
                diagnostic?.record(BrowserConnectionStage.TLS_READY)
                tls
            } else socket
            val input = BufferedInputStream(transportSocket.getInputStream(), 8192)
            val output = transportSocket.getOutputStream()
            val requestHeaders = BrowserLanProtocol.readHttpRequest(input)
            diagnostic?.record(BrowserConnectionStage.HTTP_PARSED)
            websocketRequested = requestHeaders.path == "/carplay"
            val request = BrowserLanProtocol.classifyRequest(requestHeaders, expectedHost, origin, viewerAssets)
            if (request is BrowserLanProtocol.HttpRequestKind.Health) {
                writingSince = monotonicMillis()
                output.write(BrowserLanProtocol.healthResponse(request.headOnly))
                output.flush()
                writingSince = 0
                diagnostic?.record(BrowserConnectionStage.HEALTH_SERVED)
                return
            }
            if (request is BrowserLanProtocol.HttpRequestKind.Asset) {
                writingSince = monotonicMillis()
                output.write(BrowserViewerAssets.response(request.resource, request.headOnly))
                output.flush()
                writingSince = 0
                diagnostic?.record(BrowserConnectionStage.ASSET_SERVED)
                return
            }
            val accept = (request as BrowserLanProtocol.HttpRequestKind.Upgrade).accept
            if (stopped.get()) return
            if (!claimViewer(this)) {
                diagnostic?.record(BrowserConnectionStage.WEBSOCKET_REJECTED, BrowserConnectionReason.BUSY)
                return
            }
            writingSince = monotonicMillis()
            output.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            writingSince = 0
            upgraded = true
            diagnostic?.record(BrowserConnectionStage.WEBSOCKET_ACCEPTED)
            writer = Thread({ writeLoop(output) }, "CarPlay-LAN-writer").apply { isDaemon = true; start() }
            val auth = BrowserLanProtocol.readFrame(input)
            if (auth.opcode != 1 || !BrowserLanProtocol.requestsApproval(auth.payload)) {
                diagnostic?.record(BrowserConnectionStage.WEBSOCKET_REJECTED, BrowserConnectionReason.INVALID_APPROVAL_REQUEST)
                failApproval("upgradeRequired")
                return
            }
            diagnostic?.record(BrowserConnectionStage.APPROVAL_REQUEST_RECEIVED)
            val approvalRequest = synchronized(approvalLock) {
                if (stopped.get()) return
                approvalStartedAt = monotonicMillis()
                BrowserApprovalRequest(nextRequestId.incrementAndGet(),
                    socket.inetAddress?.hostAddress ?: "unknown", approvalStartedAt + 30_000,
                    { !stopped.get() }).also { request ->
                    request.observePrompt { shown -> diagnostic?.record(
                        if (shown) BrowserConnectionStage.PROMPT_SHOWN else BrowserConnectionStage.PROMPT_UNAVAILABLE) }
                    request.observeAutomaticApproval { diagnostic?.record(BrowserConnectionStage.AUTO_APPROVED) }
                    approval = request
                }
            }
            outbound.offer(1, "{\"type\":\"approvalPending\",\"version\":2}".toByteArray())
            diagnostic?.record(BrowserConnectionStage.PROMPT_PENDING)
            onApprovalRequested(approvalRequest)
            if (!awaitApproval(input, approvalRequest)) return
            synchronized(approvalLock) {
                if (stopped.get()) return
                authenticated = true
                diagnostic?.record(BrowserConnectionStage.APPROVED)
            }
            lastRead = monotonicMillis()
            transportSocket.soTimeout = 30_000
            approval?.let { onApprovalFinished(it.id) }
            approval = null
            if (stopped.get()) return
            onAuthenticated()
            val messages = BrowserLanRateLimit(240, 1_000)
            while (!stopped.get()) {
                val frame = BrowserLanProtocol.readFrame(input)
                lastRead = monotonicMillis()
                if (!messages.allow()) throw BrowserLanProtocol.Failure(1008, BrowserConnectionReason.RATE_LIMIT)
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
            if (!stopped.get()) diagnostic?.record(
                if (upgraded || websocketRequested) BrowserConnectionStage.WEBSOCKET_REJECTED else BrowserConnectionStage.HTTP_REJECTED,
                failure.reason)
            if (upgraded) gracefulClose(byteArrayOf((failure.closeCode ushr 8).toByte(), failure.closeCode.toByte()))
        } catch (_: SocketTimeoutException) {
            val reason = when {
                !upgraded -> BrowserConnectionReason.HEADER_DEADLINE
                authenticated -> BrowserConnectionReason.IDLE_DEADLINE
                approvalStartedAt != 0L -> BrowserConnectionReason.FRAME_READ_DEADLINE
                else -> BrowserConnectionReason.APPROVAL_REQUEST_DEADLINE
            }
            stopWithReason(reason, deadline = true)
        } catch (_: EOFException) {
            if (!stopped.get() && !upgraded) diagnostic?.record(
                BrowserConnectionStage.HTTP_REJECTED, BrowserConnectionReason.INCOMPLETE_HTTP)
        } catch (_: Exception) {
            // Only a fixed classification is retained; never exception text or input.
            if (!stopped.get()) stopWithReason(BrowserConnectionReason.IO_FAILURE)
        } finally {
            stop()
            val request = approval
            request?.invalidate()
            try {
                if (request != null) onApprovalFinished(request.id)
                if (authenticated) onDisconnected()
            } finally { onFinished(this) }
        }
    }

    /** Only the reader commits a UI decision, so auth/text/disconnect callbacks remain serial. */
    private fun awaitApproval(input: BufferedInputStream, request: BrowserApprovalRequest): Boolean {
        (tlsSocket ?: socket).soTimeout = 100
        while (!stopped.get()) {
            if (monotonicMillis() - approvalStartedAt >= 30_000) {
                diagnostic?.record(BrowserConnectionStage.DEADLINE, BrowserConnectionReason.APPROVAL_DEADLINE)
                failApproval("approvalTimeout"); return false
            }
            when (request.takeDecision()) {
                1 -> return true
                2, 3 -> {
                    diagnostic?.record(BrowserConnectionStage.WEBSOCKET_REJECTED, BrowserConnectionReason.APPROVAL_REJECTED)
                    failApproval("approvalRejected"); return false
                }
            }
            // Timeout applies only to the first byte. Once a frame begins it must finish
            // with a one-second read inactivity limit plus the absolute approval watchdog;
            // a partial frame is never reinterpreted as a new frame.
            val first = try { input.read() } catch (_: SocketTimeoutException) { continue }
            if (first < 0) return false
            (tlsSocket ?: socket).soTimeout = 1_000
            val frame = BrowserLanProtocol.readFrame(SequenceInputStream(ByteArrayInputStream(byteArrayOf(first.toByte())), input))
            if (frame.opcode == 8) {
                BrowserLanProtocol.validateClose(frame.payload)
                gracefulClose(frame.payload)
            } else {
                diagnostic?.record(BrowserConnectionStage.WEBSOCKET_REJECTED, BrowserConnectionReason.APPROVAL_REJECTED)
                failApproval("approvalRejected") // no commands or media before consent
            }
            return false
        }
        return false
    }

    private fun failApproval(code: String) {
        // Write only a fixed protocol error before close, never session/media information.
        outbound.offer(1, "{\"type\":\"error\",\"code\":\"$code\",\"version\":2}".toByteArray())
        // finishWithClose discards queued controls, so encode the same public reason in close.
        gracefulClose(byteArrayOf(3, 0xf0.toByte()) + code.toByteArray())
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
    class Failure(val closeCode: Int = 1002,
        val reason: BrowserConnectionReason = BrowserConnectionReason.INVALID_FRAME) :
        IOException("Invalid browser bridge protocol")
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

    data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>)
    sealed class HttpRequestKind {
        data class Health(val headOnly: Boolean) : HttpRequestKind()
        data class Asset(val resource: BrowserViewerAssets.Resource, val headOnly: Boolean) : HttpRequestKind()
        data class Upgrade(val accept: String) : HttpRequestKind()
    }

    private fun invalid(reason: BrowserConnectionReason): Nothing = throw Failure(reason = reason)

    fun readHttpRequest(input: InputStream): HttpRequest {
        val bytes = ByteArray(MAX_HEADERS)
        var size = 0
        while (size < bytes.size) {
            val value = input.read()
            if (value < 0) throw EOFException()
            if (value != 9 && value != 10 && value != 13 && value !in 32..126) {
                invalid(BrowserConnectionReason.MALFORMED_HTTP)
            }
            bytes[size++] = value.toByte()
            if (size >= 4 && bytes[size - 4] == 13.toByte() && bytes[size - 3] == 10.toByte() &&
                bytes[size - 2] == 13.toByte() && bytes[size - 1] == 10.toByte()
            ) break
        }
        if (size < 4 || bytes[size - 4] != 13.toByte() || bytes[size - 3] != 10.toByte() ||
            bytes[size - 2] != 13.toByte() || bytes[size - 1] != 10.toByte()
        ) invalid(BrowserConnectionReason.HEADERS_TOO_LARGE)
        val lines = String(bytes, 0, size - 4, Charsets.US_ASCII).split("\r\n")
        if (lines.size > 65) invalid(BrowserConnectionReason.HEADERS_TOO_LARGE)
        val requestLine = Regex("([A-Z]+) ([^ \t\r\n]+) HTTP/1\\.1").matchEntire(lines.first())
            ?: invalid(BrowserConnectionReason.MALFORMED_HTTP)
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) invalid(BrowserConnectionReason.MALFORMED_HTTP)
            val name = line.substring(0, colon)
            if (!name.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))) invalid(BrowserConnectionReason.MALFORMED_HTTP)
            val value = line.substring(colon + 1).trim(' ', '\t')
            if (value.any { it == '\r' || it == '\n' }) invalid(BrowserConnectionReason.MALFORMED_HTTP)
            if (headers.put(name.lowercase(Locale.ROOT), value) != null) invalid(BrowserConnectionReason.MALFORMED_HTTP)
        }
        return HttpRequest(requestLine.groupValues[1], requestLine.groupValues[2], headers)
    }

    fun classifyRequest(request: HttpRequest, expectedHost: String, origin: String, assets: BrowserViewerAssets? = null): HttpRequestKind {
        val headers = request.headers
        val asset = assets?.resource(request.path)
        if (request.path != "/health" && request.path != "/carplay" && asset == null) invalid(BrowserConnectionReason.INVALID_PATH)
        val health = request.path == "/health"
        val navigation = health || asset != null
        if (request.method != "GET" && !(navigation && request.method == "HEAD")) invalid(BrowserConnectionReason.INVALID_METHOD)
        if (headers["host"] != expectedHost) invalid(BrowserConnectionReason.INVALID_HOST)
        // A direct health navigation need not carry Origin; when present it is still pinned.
        if ((!navigation || "origin" in headers) && headers["origin"] != origin) invalid(BrowserConnectionReason.INVALID_ORIGIN)
        if ("transfer-encoding" in headers || (headers["content-length"] != null && headers["content-length"] != "0")) {
            invalid(BrowserConnectionReason.UNSUPPORTED_BODY)
        }
        val connectionUpgrade = headers["connection"]?.split(',')?.any { it.trim().equals("upgrade", ignoreCase = true) } == true
        if (navigation) {
            if ("upgrade" in headers || connectionUpgrade || headers.keys.any { it.startsWith("sec-websocket-") }) {
                invalid(BrowserConnectionReason.INVALID_UPGRADE)
            }
            return if (asset != null) HttpRequestKind.Asset(asset, request.method == "HEAD")
                else HttpRequestKind.Health(request.method == "HEAD")
        }
        if (!headers["upgrade"].equals("websocket", ignoreCase = true) || !connectionUpgrade) {
            invalid(BrowserConnectionReason.INVALID_UPGRADE)
        }
        if (headers["sec-websocket-version"] != "13") invalid(BrowserConnectionReason.INVALID_WEBSOCKET_VERSION)
        val key = headers["sec-websocket-key"] ?: invalid(BrowserConnectionReason.INVALID_WEBSOCKET_KEY)
        val decoded = try { Base64.getDecoder().decode(key) } catch (_: IllegalArgumentException) {
            invalid(BrowserConnectionReason.INVALID_WEBSOCKET_KEY)
        }
        if (decoded.size != 16 || Base64.getEncoder().encodeToString(decoded) != key) {
            invalid(BrowserConnectionReason.INVALID_WEBSOCKET_KEY)
        }
        return HttpRequestKind.Upgrade(Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))))
    }

    fun handshake(input: InputStream, expectedHost: String, origin: String): String {
        val request = classifyRequest(readHttpRequest(input), expectedHost, origin)
        return (request as? HttpRequestKind.Upgrade)?.accept ?: invalid(BrowserConnectionReason.INVALID_PATH)
    }

    /** Fixed public marker only. Never add identity, network/session state or diagnostic history. */
    fun healthResponse(headOnly: Boolean): ByteArray {
        val body = "{\"service\":\"diplay-browser\",\"protocol\":2,\"build\":\"connection-diag-v1\"}\n"
        val headers = "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\nConnection: close\r\n" +
            "Cache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n\r\n"
        return (headers + if (headOnly) "" else body).toByteArray(Charsets.UTF_8)
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

    fun requestsApproval(payload: ByteArray): Boolean {
        // Deliberately narrow v2 request grammar: exactly these two fields, either order.
        // No old token, extra field, nested JSON, duplicate key or coercion can authorize.
        // Escape both literal braces: Android ICU rejects a bare closing brace, unlike the JVM.
        val text = utf8(payload)
        return Regex("""\s*\{\s*"type"\s*:\s*"requestApproval"\s*,\s*"version"\s*:\s*2\s*\}\s*""").matches(text) ||
            Regex("""\s*\{\s*"version"\s*:\s*2\s*,\s*"type"\s*:\s*"requestApproval"\s*\}\s*""").matches(text)
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

