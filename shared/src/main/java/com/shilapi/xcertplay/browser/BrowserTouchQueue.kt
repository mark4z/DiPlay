package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.ArrayDeque

/** Bounded input snapshots: preserve topology boundaries and coalesce subsequent moves. */
internal class BrowserTouchQueue(private val capacity: Int = 16) {
    private data class Snapshot(val contacts: List<AirPlayContact>, val boundary: Boolean)
    private val queue = ArrayDeque<Snapshot>()
    // Retain this across poll(): a move arriving after DOWN was consumed is still
    // a move. Only clear/overflow starts a new released input generation.
    private var lastTopology = 0
    @Synchronized fun offer(contacts: List<AirPlayContact>) {
        val snapshot = contacts.toList()
        val nextTopology = topology(snapshot)
        val boundary = nextTopology != lastTopology
        if (!boundary && queue.peekLast()?.boundary == false) {
            queue.removeLast(); queue.addLast(Snapshot(snapshot, false))
        } else if (queue.size >= capacity) {
            // Overflow is a cancelled gesture, never a stuck press or an unbounded backlog.
            queue.clear(); queue.addLast(Snapshot(emptyList(), true))
            lastTopology = 0
            return
        } else queue.addLast(Snapshot(snapshot, boundary))
        lastTopology = nextTopology
    }
    @Synchronized fun poll(): List<AirPlayContact>? = queue.pollFirst()?.contacts
    @Synchronized fun clear() { queue.clear(); lastTopology = 0 }
    private fun topology(contacts: List<AirPlayContact>): Int = contacts.fold(0) { bits, p ->
        if (p.down) bits or (1 shl p.id) else bits
    }
}
