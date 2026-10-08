package com.shilapi.xcertplay.browser

import java.util.ArrayDeque

/** Fixed vocabulary only: diagnostics never retain request headers, addresses or payloads. */
enum class BrowserConnectionStage {
    TCP_ACCEPTED, TLS_HANDSHAKE_STARTED, TLS_READY, HTTP_PARSED, HTTP_REJECTED, HEALTH_SERVED, ASSET_SERVED,
    WEBSOCKET_ACCEPTED, WEBSOCKET_REJECTED, APPROVAL_REQUEST_RECEIVED,
    PROMPT_PENDING, PROMPT_SHOWN, PROMPT_UNAVAILABLE, AUTO_APPROVED, APPROVED, CLOSED, DEADLINE,
}

enum class BrowserConnectionReason {
    NONE, BUSY, RATE_LIMIT, NON_PRIVATE_PEER, SERVER_STOPPED, PREFLIGHT_LIMIT,
    MALFORMED_HTTP, HEADERS_TOO_LARGE, INVALID_METHOD, INVALID_PATH, INVALID_HOST,
    INVALID_ORIGIN, INVALID_UPGRADE, INVALID_WEBSOCKET_VERSION, INVALID_WEBSOCKET_KEY,
    UNSUPPORTED_BODY, INCOMPLETE_HTTP, INVALID_FRAME, INVALID_APPROVAL_REQUEST,
    APPROVAL_REJECTED, HEADER_DEADLINE, APPROVAL_REQUEST_DEADLINE, APPROVAL_DEADLINE,
    IDLE_DEADLINE, FRAME_READ_DEADLINE, WRITE_DEADLINE, IO_FAILURE,
}

data class BrowserConnectionEvent(
    val connectionId: Long,
    val timestampMillis: Long,
    val elapsedMillis: Long,
    val stage: BrowserConnectionStage,
    val reason: BrowserConnectionReason,
)

/** Session-local ring, bounded even when peers repeatedly send malformed input. */
internal class BrowserConnectionDiagnostics(private val capacity: Int = 128) {
    private val events = ArrayDeque<BrowserConnectionEvent>()
    private var nextId = 0L
    private val counts = LongArray(BrowserConnectionStage.values().size)

    init { require(capacity in 1..128) }

    @Synchronized fun begin(): Attempt = Attempt(++nextId, System.nanoTime() / 1_000_000).also {
        it.record(BrowserConnectionStage.TCP_ACCEPTED)
    }

    @Synchronized fun snapshot(): List<BrowserConnectionEvent> = events.toList()

    @Synchronized fun report(): String = buildString {
        append("Browser connection diagnostics v1 (local; last ").append(capacity).append(" events)\n")
        append("Session totals:")
        for (stage in BrowserConnectionStage.values()) {
            if (counts[stage.ordinal] > 0) append(' ').append(stage.name).append('=').append(counts[stage.ordinal])
        }
        append('\n')
        for (event in snapshot()) {
            append('#').append(event.connectionId).append(' ')
            append(event.timestampMillis).append(" +").append(event.elapsedMillis).append("ms ")
            append(event.stage.name)
            if (event.reason != BrowserConnectionReason.NONE) append(' ').append(event.reason.name)
            append('\n')
        }
    }

    inner class Attempt internal constructor(val id: Long, private val startedAt: Long) {
        private var terminal = false
        fun record(stage: BrowserConnectionStage, reason: BrowserConnectionReason = BrowserConnectionReason.NONE) {
            synchronized(this@BrowserConnectionDiagnostics) {
                if (terminal) return
                if (stage == BrowserConnectionStage.CLOSED) terminal = true
                if (counts[stage.ordinal] < Long.MAX_VALUE) counts[stage.ordinal]++
                if (events.size == capacity) events.removeFirst()
                events.addLast(BrowserConnectionEvent(id, System.currentTimeMillis(),
                    (System.nanoTime() / 1_000_000 - startedAt).coerceAtLeast(0), stage, reason))
            }
        }
    }
}

