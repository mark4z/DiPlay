package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class PerformanceDiagnosticsTest {
    @Test fun disabledDoesNotReadClockOrCollect() {
        val recorder = PerformanceRecorder { error("disabled capture must not read clock") }
        assertEquals(0L, recorder.token())
        assertEquals(0L, recorder.now(1))
        recorder.record(PerformanceMetric.TOUCH_SEND, 100, 1)
        recorder.count(PerformanceCounter.TOUCH_SENT, 1)
        recorder.touchQueued(1)
        recorder.touchSent(1, 10)
        recorder.onMainFrameReceived(1, 100)
        recorder.resetSession()
        assertNull(recorder.logIfDue())
        assertTrue(recorder.snapshot().contains("touch_sent=0"))
    }

    @Test fun histogramIsBoundedAndHasExactRecentPercentiles() {
        val recorder = PerformanceRecorder { 10 }
        recorder.setEnabled(true)
        val capture = recorder.token()
        recorder.record(PerformanceMetric.VIDEO_QUEUE_WAIT, 10_000_000_000L, capture)
        repeat(10_000) { recorder.record(PerformanceMetric.VIDEO_QUEUE_WAIT, 1_000_000, capture) }
        val report = recorder.snapshot()
        assertTrue(report.contains("video_queue_wait n=10001 window=512 p50=1.000 p95=1.000 p99=1.000 max=10000.000 over250ms=1 over2000ms=1"))
        assertTrue(report.length < 12_000)
        assertTrue(report.lineSequence().all { it.length < 700 })
    }

    @Test fun togglesInvalidateOutstandingWorkAndStartFreshCapture() {
        var now = 0L
        val recorder = PerformanceRecorder { now }
        recorder.setEnabled(true)
        val first = recorder.token()
        recorder.count(PerformanceCounter.TOUCH_SENT, first)
        recorder.setEnabled(true)
        assertEquals(first, recorder.token())
        recorder.setEnabled(false)
        recorder.count(PerformanceCounter.TOUCH_SENT, first)
        assertTrue(recorder.snapshot().contains("touch_sent=1"))
        now = 100L
        recorder.setEnabled(true)
        assertNotEquals(first, recorder.token())
        recorder.count(PerformanceCounter.TOUCH_SENT, first)
        assertTrue(recorder.snapshot().contains("touch_sent=0"))
    }

    @Test fun sessionResetClearsPendingProxyAndDepthButPreservesCaptureTotals() {
        val recorder = PerformanceRecorder { 10 }
        recorder.setEnabled(true)
        val old = recorder.token()
        recorder.touchQueued(old)
        recorder.touchSent(old, 100)
        recorder.resetSession()
        recorder.onMainFrameReceived(recorder.token(), 1000)
        recorder.record(PerformanceMetric.TOUCH_SEND, 1000, old)
        val report = recorder.snapshot()
        assertTrue(report.contains("touch_queue_depth=0 peak=1"))
        assertTrue(report.contains("touch_sent_to_next_frame_proxy n=0"))
        assertTrue(report.contains("touch_send n=0"))
        assertTrue(report.contains("touch_queued=1"))
    }

    @Test fun proxyUsesFirstCompletedSendAndConsumesOnlyOnce() {
        val recorder = PerformanceRecorder { 10 }
        recorder.setEnabled(true)
        val capture = recorder.token()
        recorder.touchSent(capture, 1_000_000)
        recorder.touchSent(capture, 2_000_000)
        recorder.onMainFrameReceived(capture, 5_000_000)
        recorder.onMainFrameReceived(capture, 9_000_000)
        assertTrue(recorder.snapshot().contains("touch_sent_to_next_frame_proxy n=1 window=1 p50=4.000"))
        assertTrue(recorder.snapshot().contains("touch_proxy_coalesced=1"))
    }

    @Test fun earlierFrameDoesNotConsumePendingTouchAndRejectionIsNotStarted() {
        val recorder = PerformanceRecorder { 0 }
        recorder.setEnabled(true)
        val capture = recorder.token()
        recorder.touchQueued(capture)
        recorder.touchRejected(capture)
        recorder.touchSent(capture, 5_000_000)
        recorder.onMainFrameReceived(capture, 4_000_000)
        recorder.onMainFrameReceived(capture, 8_000_000)
        val report = recorder.snapshot()
        assertTrue(report.contains("touch_sent_to_next_frame_proxy n=1 window=1 p50=3.000"))
        assertTrue(report.contains("touch_started=0"))
        assertTrue(report.contains("touch_rejected=1"))
        assertTrue(report.contains("touch_queue_depth=0 peak=1"))
    }

    @Test fun periodicSummariesAreRateLimitedAndOffRetainsSnapshot() {
        var now = 0L
        val recorder = PerformanceRecorder { now }
        recorder.setEnabled(true)
        assertNull(recorder.logIfDue())
        now = 10_000_000_000
        assertNotNull(recorder.logIfDue())
        assertNull(recorder.logIfDue())
        recorder.setEnabled(false)
        now = 30_000_000_000
        assertNull(recorder.logIfDue())
        assertTrue(recorder.snapshot().contains("captureDurationMs=10000"))
    }

    @Test fun longGapsAreNotHiddenAndNegativeSamplesAreRejected() {
        val recorder = PerformanceRecorder { 0 }
        recorder.setEnabled(true)
        val capture = recorder.token()
        recorder.record(PerformanceMetric.VIDEO_RECEIVE_GAP, -1, capture)
        recorder.record(PerformanceMetric.VIDEO_RECEIVE_GAP, 3_000_000_000, capture)
        assertTrue(recorder.snapshot().contains("video_receive_gap n=1 window=1 p50=3000.000 p95=3000.000 p99=3000.000 max=3000.000 over250ms=1 over2000ms=1"))
    }
}
