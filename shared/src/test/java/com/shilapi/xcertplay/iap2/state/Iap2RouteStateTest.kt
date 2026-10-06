package com.shilapi.xcertplay.iap2.state

import com.shilapi.xcertplay.iap2.body.Iap2BodyBuilder
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2RouteStateTest {
    @Test
    fun retainsAppleManeuverCodesAndFullRouteMetadata() {
        val state = Iap2RouteState()
        state.update(Iap2RouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) {
            u16(1, 7); u8(3, 29); u8(8, 1); string(4, " Exit Road ")
        }
        val change = state.update(Iap2RouteState.ROUTE_GUIDANCE_UPDATE) {
            u8(1, 1); string(3, "Current Road"); u64(5, 1_800_000_000L)
            u64(6, 90); u32(7, 1_200); u32(0x0a, 350); u16List(0x0d, listOf(7))
        }

        assertEquals(Iap2RouteChange.GUIDANCE, change)
        assertEquals(Iap2RouteManeuver(350, 29, 1, "Exit Road", 90, 1_200, 1_800_000_000L), state.current())
    }

    @Test
    fun preservesUnknownAppleTypesAndFallsBackToCurrentRoad() {
        val state = Iap2RouteState()
        state.update(Iap2RouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) { u16(1, 1); u8(3, 255) }
        state.update(Iap2RouteState.ROUTE_GUIDANCE_UPDATE) {
            u8(1, 1); string(3, "Main Street"); u32(0x0a, 0xffff_ffffL); u16List(0x0d, listOf(1))
        }
        assertEquals(Iap2RouteManeuver(Int.MAX_VALUE, 255, 0, "Main Street"), state.current())
    }

    @Test
    fun omittedFieldsRetainTheCurrentInstructionAndMalformedFramesDoNotRefreshIt() {
        var now = 0L
        val state = populatedState { now }
        state.update(Iap2RouteState.ROUTE_GUIDANCE_UPDATE) { u32(0x0a, 42) }
        val before = state.current()
        assertEquals(42, before!!.distanceMeters)
        now = Iap2RouteState.STALE_ROUTE_NS - 1
        assertEquals(Iap2RouteChange.NONE,
            state.accept(Iap2RouteState.ROUTE_GUIDANCE_UPDATE, byteArrayOf(0, 8, 0, 1, 1)))
        assertEquals(before, state.current())
        now++
        assertNull(state.current())
        assertEquals(Iap2RouteChange.GUIDANCE,
            state.update(Iap2RouteState.ROUTE_GUIDANCE_UPDATE) { u32(0x0a, 20) })
        assertEquals(20, state.current()!!.distanceMeters)
    }

    @Test
    fun anEmptyListHasAGracePeriodAndDoesNotDiscardCachedManeuvers() {
        var now = 0L
        val state = populatedState { now }
        assertEquals(Iap2RouteChange.NONE,
            state.update(Iap2RouteState.ROUTE_GUIDANCE_UPDATE) { u16List(0x0d, emptyList()) })
        now = Iap2RouteState.EMPTY_LIST_HIDE_NS - 1
        assertEquals(2, state.current()!!.type)
        now++
        assertNull(state.current())
        assertEquals(Iap2RouteChange.GUIDANCE,
            state.update(Iap2RouteState.ROUTE_GUIDANCE_UPDATE) { u16List(0x0d, listOf(1)) })
        assertEquals(2, state.current()!!.type)
    }

    @Test
    fun noRouteAndArrivalClearTheDefaultState() {
        for (status in listOf(0, 2)) {
            val state = populatedState()
            assertEquals(Iap2RouteChange.CLEAR,
                state.update(Iap2RouteState.ROUTE_GUIDANCE_UPDATE) { u8(1, status) })
            assertNull(state.current())
            assertFalse(state.clear())
        }
    }

    @Test
    fun retainedNoRouteDoesNotRefreshLifetimeOrMetadataAndArrivalStillClears() {
        var now = 0L
        val state = populatedState(keepAcrossNoRoute = true, nanoTime = { now })
        val before = state.current()
        now = Iap2RouteState.STALE_ROUTE_NS - 1
        assertEquals(Iap2RouteChange.NONE, state.update(Iap2RouteState.ROUTE_GUIDANCE_UPDATE) {
            u8(1, 0); string(3, "Teardown road"); u32(0x0a, 0)
        })
        assertEquals(before, state.current())
        now++
        assertNull(state.current())
        state.update(Iap2RouteState.ROUTE_GUIDANCE_UPDATE) { u32(0x0a, 12) }
        assertEquals(Iap2RouteChange.CLEAR,
            state.update(Iap2RouteState.ROUTE_GUIDANCE_UPDATE) { u8(1, 2) })
        assertNull(state.current())
    }

    @Test
    fun clearDropsCachedManeuversAndOtherMessagesLeaveStateAlone() {
        val state = populatedState()
        val before = state.current()
        assertEquals(Iap2RouteChange.NONE, state.update(0x5001) { u8(1, 0) })
        assertEquals(before, state.current())
        assertTrue(state.clear())
        state.update(Iap2RouteState.ROUTE_GUIDANCE_UPDATE) { u8(1, 1); u16List(0x0d, listOf(1)) }
        assertNull(state.current())
    }

    private fun populatedState(
        keepAcrossNoRoute: Boolean = false,
        nanoTime: () -> Long = System::nanoTime,
    ): Iap2RouteState = Iap2RouteState(nanoTime, keepAcrossNoRoute = keepAcrossNoRoute).also { state ->
        state.update(Iap2RouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) { u16(1, 1); u8(3, 2) }
        state.update(Iap2RouteState.ROUTE_GUIDANCE_UPDATE) {
            u8(1, 1); string(3, "Main Street"); u32(0x0a, 25); u16List(0x0d, listOf(1))
        }
    }

    private fun Iap2RouteState.update(id: Int, body: Iap2BodyBuilder.() -> Unit): Iap2RouteChange =
        accept(id, Iap2Messages.buildRaw(id, body).payload)
}
