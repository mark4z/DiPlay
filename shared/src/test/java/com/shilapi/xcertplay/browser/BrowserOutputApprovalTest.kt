package com.shilapi.xcertplay.browser

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BrowserOutputApprovalTest {
    private val owners = mutableListOf<Any>()

    @After fun cleanUp() {
        owners.forEach(BrowserOutput::unregisterApprovalUi)
        BrowserOutput.stop()
    }

    @Test fun aConnectionWithoutResumedUiIsRejected() {
        val request = request(1)
        dispatch(request)
        assertEquals(2, request.takeDecision())
        assertFalse(BrowserOutput.viewerConnected)
        assertFalse(BrowserOutput.browserTouchOwned)
    }

    @Test fun pausingRejectsPendingRequestAndPreventsLateApproval() {
        val owner = Any().also(owners::add)
        var shown: BrowserApprovalRequest? = null
        val finished = mutableListOf<Long>()
        BrowserOutput.registerApprovalUi(owner, { shown = it }, { finished.add(it) })
        val request = request(2)
        dispatch(request)
        assertSame(request, shown)
        assertTrue(BrowserOutput.isApprovalPending(request))
        BrowserOutput.unregisterApprovalUi(owner)
        assertFalse(BrowserOutput.isApprovalPending(request))
        assertFalse(request.approve())
        assertEquals(2, request.takeDecision())
        assertEquals(listOf(2L), finished)
    }

    @Test fun aNewActivityCannotInheritTheOldApprovalDialog() {
        val first = Any().also(owners::add)
        val second = Any().also(owners::add)
        val finished = mutableListOf<Long>()
        BrowserOutput.registerApprovalUi(first, {}, { finished.add(it) })
        val old = request(3)
        dispatch(old)
        var shown: BrowserApprovalRequest? = null
        BrowserOutput.registerApprovalUi(second, { shown = it }, {})
        assertEquals(2, old.takeDecision())
        assertEquals(listOf(3L), finished)
        BrowserOutput.unregisterApprovalUi(first)
        val fresh = request(4)
        dispatch(fresh)
        assertSame(fresh, shown)
        assertTrue(BrowserOutput.isApprovalPending(fresh))
    }

    @Test fun stopDismissesAndInvalidatesPendingApproval() {
        val owner = Any().also(owners::add)
        val finished = mutableListOf<Long>()
        BrowserOutput.registerApprovalUi(owner, {}, { finished.add(it) })
        val request = request(5)
        dispatch(request)
        BrowserOutput.stop()
        assertFalse(BrowserOutput.isApprovalPending(request))
        assertFalse(request.approve())
        assertEquals(2, request.takeDecision())
        assertEquals(listOf(5L), finished)
    }

    @Test fun aStoppedServerCannotAskAResumedActivityToApprove() {
        val owner = Any().also(owners::add)
        var shown = 0
        BrowserOutput.registerApprovalUi(owner, { shown++ }, {})
        val generation = generation()
        BrowserOutput.stop()
        val request = request(6)
        dispatch(request, generation)
        assertEquals(0, shown)
        assertEquals(2, request.takeDecision())
    }

    @Test fun newerCandidateDismissesOldPromptAndItsLateFinishCannotDismissNewPrompt() {
        val owner = Any().also(owners::add)
        val events = mutableListOf<String>()
        BrowserOutput.registerApprovalUi(owner, { events.add("show:${it.id}") }, { events.add("finish:$it") })
        val old = request(7)
        dispatch(old)
        val fresh = request(8)
        dispatch(fresh)
        assertFalse(old.approve())
        assertFalse(BrowserOutput.isApprovalPending(old))
        assertTrue(BrowserOutput.isApprovalPending(fresh))
        assertEquals(listOf("show:7", "finish:7", "show:8"), events)
        BrowserOutput::class.java.getDeclaredMethod("finishApproval", java.lang.Long.TYPE, java.lang.Long.TYPE)
            .apply { isAccessible = true }.invoke(BrowserOutput, old.id, generation())
        assertTrue(BrowserOutput.isApprovalPending(fresh))
        assertEquals(listOf("show:7", "finish:7", "show:8"), events)
        assertTrue(fresh.approve())
    }

    private fun request(id: Long) = BrowserApprovalRequest(id, "192.168.40.5",
        System.nanoTime() / 1_000_000L + 30_000L)

    private fun generation(): Long = BrowserOutput::class.java.getDeclaredField("serverGeneration")
        .apply { isAccessible = true }.getLong(null)

    private fun dispatch(request: BrowserApprovalRequest, generation: Long = generation()) {
        BrowserOutput::class.java.getDeclaredMethod("requestApproval", BrowserApprovalRequest::class.java,
            java.lang.Long.TYPE).apply { isAccessible = true }.invoke(BrowserOutput, request, generation)
    }
}
