package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(
        val nalus: ByteArray,
        // Required by the existing age limit even when optional diagnostics are disabled.
        val receivedNs: Long = System.nanoTime(),
        val performanceToken: Long = 0,
    ) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    data object Resync : VideoJob
}

/** Do not resume dependent pictures after losing a reference frame. */
internal class VideoReferenceChain {
    var needsKeyFrame = true
        private set
    fun reset() { needsKeyFrame = true }
    fun accepts(bytes: ByteArray, codec: VideoCodec): Boolean =
        !needsKeyFrame || MediaCodecSupport.isRandomAccess(bytes, codec)
    fun onQueued() { needsKeyFrame = false }
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Wi-Fi delivers frames in bursts after a radio gap; the decoder's 250 ms age check bounds latency.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()

    @Synchronized fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            val frames = jobs.filterIsInstance<VideoJob.Frame>()
            if (frames.size >= maxFrames || frames.sumOf { it.nalus.size.toLong() } + job.nalus.size > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            // A single oversized frame is also a lost reference chain.
            if (job.nalus.size > maxBytes) {
                if (job.performanceToken != 0L) {
                    PerformanceDiagnostics.count(PerformanceCounter.VIDEO_QUEUE_DROPPED, job.performanceToken)
                }
                recordDepth()
                return
            }
        }
        jobs.offer(job)
        recordDepth()
    }

    @Synchronized fun discardFrames(reason: PerformanceCounter = PerformanceCounter.VIDEO_QUEUE_DROPPED) {
        val token = PerformanceDiagnostics.token()
        var dropped = 0L
        jobs.removeIf {
            if (it is VideoJob.Frame && token != 0L && it.performanceToken == token) dropped++
            it is VideoJob.Frame || it is VideoJob.Resync
        }
        if (token != 0L) PerformanceDiagnostics.count(reason, token, dropped)
        recordDepth()
    }

    fun poll(timeoutMillis: Long): VideoJob? = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS).also {
        if (it != null) recordDepth()
    }

    private fun recordDepth() {
        val token = PerformanceDiagnostics.token()
        if (token != 0L) {
            PerformanceDiagnostics.depth(PerformanceCounter.VIDEO_QUEUE_DEPTH,
                jobs.count { it is VideoJob.Frame && it.performanceToken == token }, token)
        }
    }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. */
internal object VideoInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
