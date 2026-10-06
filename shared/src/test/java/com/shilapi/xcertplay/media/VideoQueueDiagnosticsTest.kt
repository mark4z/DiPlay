package com.shilapi.xcertplay.media

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class VideoQueueDiagnosticsTest {
    @Before fun enableFreshCapture() {
        PerformanceDiagnostics.setEnabled(false)
        PerformanceDiagnostics.setEnabled(true)
    }

    @After fun disableCapture() { PerformanceDiagnostics.setEnabled(false) }

    private fun frame(bytes: Int = 1) = VideoJob.Frame(ByteArray(bytes), performanceToken = PerformanceDiagnostics.token())
    private fun snapshotContains(line: String) = assertTrue(PerformanceDiagnostics.snapshot(), PerformanceDiagnostics.snapshot().contains(line))

    @Test fun overflowCountsOnlyDiscardedPicturesAndPreservesResyncOrder() {
        val queue = VideoDecodeQueue(maxFrames = 2)
        queue.offer(frame())
        queue.offer(frame())
        queue.offer(frame())
        snapshotContains("video_queue_dropped=2")
        snapshotContains("video_queue_depth=1 peak=2")
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertTrue(queue.poll(0) is VideoJob.Frame)
        snapshotContains("video_queue_depth=0 peak=2")
    }

    @Test fun oversizedPictureIsCountedAsDroppedWithoutChangingQueuePolicy() {
        val queue = VideoDecodeQueue(maxBytes = 2)
        queue.offer(frame(3))
        snapshotContains("video_queue_dropped=1")
        snapshotContains("video_queue_depth=0 peak=0")
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertNull(queue.poll(0))
    }

    @Test fun oldCaptureFramesCannotInflateNewCaptureDepthOrDropCount() {
        val queue = VideoDecodeQueue()
        queue.offer(frame())
        PerformanceDiagnostics.setEnabled(false)
        PerformanceDiagnostics.setEnabled(true)
        queue.offer(frame())
        snapshotContains("video_queue_depth=1 peak=1")
        queue.discardFrames(PerformanceCounter.VIDEO_STALE_DROPPED)
        snapshotContains("video_stale_dropped=1")
        snapshotContains("video_queue_depth=0 peak=1")
    }

    @Test fun disabledCaptureLeavesQueueBehaviorAndCountersUnchanged() {
        PerformanceDiagnostics.setEnabled(false)
        val before = PerformanceDiagnostics.snapshot()
        val queue = VideoDecodeQueue(maxFrames = 1)
        queue.offer(frame())
        queue.offer(frame())
        assertEquals(VideoJob.Resync, queue.poll(0))
        assertTrue(queue.poll(0) is VideoJob.Frame)
        assertNull(queue.poll(0))
        assertEquals(before, PerformanceDiagnostics.snapshot())
    }
}
