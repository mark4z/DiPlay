package com.shilapi.xcertplay.browser

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.ArrayDeque

/** Bounded input snapshots: only moves with identical down slots may coalesce. */
internal class BrowserTouchQueue(private val capacity: Int = 16) {
    private val queue = ArrayDeque<List<AirPlayContact>>()
    @Synchronized fun offer(contacts: List<AirPlayContact>) {
        val snapshot = contacts.toList()
        if (queue.isNotEmpty() && topology(queue.peekLast()!!) == topology(snapshot)) {
            queue.removeLast(); queue.addLast(snapshot)
        } else if (queue.size >= capacity) {
            // Overflow is a cancelled gesture, never a stuck press or an unbounded backlog.
            queue.clear(); queue.addLast(emptyList())
        } else queue.addLast(snapshot)
    }
    @Synchronized fun poll(): List<AirPlayContact>? = queue.pollFirst()
    @Synchronized fun clear() = queue.clear()
    private fun topology(contacts: List<AirPlayContact>): Int = contacts.fold(0) { bits, p ->
        if (p.down) bits or (1 shl p.id) else bits
    }
}
