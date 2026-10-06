package com.shilapi.xcertplay.browser
import com.shilapi.xcertplay.airplay.AirPlayContact
import org.junit.Assert.*
import org.junit.Test
class BrowserTouchQueueTest {
    private fun down(x: Double) = listOf(AirPlayContact(0,x,0.5,true))
    @Test fun retainsTapBoundariesButCoalescesMoves() {
        val q = BrowserTouchQueue()
        q.offer(down(0.1)); q.offer(down(0.2)); q.offer(emptyList()); q.offer(down(0.3))
        assertEquals(down(0.2),q.poll()); assertEquals(emptyList<AirPlayContact>(),q.poll())
        assertEquals(down(0.3),q.poll()); assertNull(q.poll())
    }
    @Test fun overflowForcesReleaseAndBoundsQueue() {
        val q = BrowserTouchQueue(2)
        q.offer(down(0.1)); q.offer(emptyList()); q.offer(down(0.2))
        assertEquals(emptyList<AirPlayContact>(),q.poll()); assertNull(q.poll())
    }
}
