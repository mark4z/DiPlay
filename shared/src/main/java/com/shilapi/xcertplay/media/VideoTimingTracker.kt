package com.shilapi.xcertplay.media

/**
 * Optional, bounded PTS correlation only. Never manufactures or changes codec timestamps.
 * Completed entries remain as bounded tombstones so a repeated PTS cannot match another frame.
 * One instance belongs to one codec and one diagnostics capture; invalidate it on either change.
 */
internal class VideoTimingTracker(private val capacity: Int = 240) {
    data class Timing(val receivedNs: Long, val fedNs: Long, val releasedNs: Long = 0)
    data class Added(val ambiguous: Boolean, val evicted: Int)
    data class Observed(val timing: Timing?, val gapNs: Long, val excluded: Boolean = false)
    private enum class Stage { INPUT, RELEASED, COMPLETE, AMBIGUOUS, EXCLUDED }
    private data class Entry(var timing: Timing?, var stage: Stage)

    private val entries = LinkedHashMap<Long, Entry>()
    private var active = true
    private var lastPtsUs: Long? = null
    private var lastReleaseNs = 0L
    private var lastRenderNs = 0L

    init { require(capacity > 0) }

    @Synchronized
    fun onInput(ptsUs: Long, receivedNs: Long, fedNs: Long): Added {
        if (!active) return Added(false, 0)
        val previous = entries[ptsUs]
        val ambiguous = previous != null || lastPtsUs == ptsUs
        lastPtsUs = ptsUs
        if (previous != null) {
            previous.timing = null
            if (previous.stage != Stage.EXCLUDED) previous.stage = Stage.AMBIGUOUS
            return Added(true, 0)
        }
        var evicted = 0
        if (entries.size >= capacity) {
            val iterator = entries.entries.iterator()
            val removed = iterator.next().value
            if (removed.stage == Stage.INPUT || removed.stage == Stage.RELEASED) evicted = 1
            iterator.remove()
        }
        entries[ptsUs] = if (ambiguous) Entry(null, Stage.AMBIGUOUS)
        else Entry(Timing(receivedNs, fedNs), Stage.INPUT)
        return Added(ambiguous, evicted)
    }

    @Synchronized
    fun onRelease(ptsUs: Long, releasedNs: Long, expectRender: Boolean): Observed {
        if (!active) return Observed(null, 0)
        val gap = if (lastReleaseNs != 0L && releasedNs >= lastReleaseNs) releasedNs - lastReleaseNs else 0
        if (releasedNs > lastReleaseNs) lastReleaseNs = releasedNs
        val entry = entries[ptsUs]
        if (entry?.stage != Stage.INPUT) return Observed(null, gap)
        val timing = entry.timing ?: return Observed(null, gap)
        if (releasedNs < timing.fedNs) return Observed(null, gap)
        val released = timing.copy(releasedNs = releasedNs)
        entry.timing = if (expectRender) released else null
        entry.stage = if (expectRender) Stage.RELEASED else Stage.COMPLETE
        return Observed(released, gap)
    }

    @Synchronized
    fun onRender(ptsUs: Long, renderedNs: Long): Observed {
        if (!active) return Observed(null, 0)
        val entry = entries[ptsUs]
        if (entry?.stage == Stage.EXCLUDED) return Observed(null, 0, excluded = true)
        // Callbacks may be batched or delivered out of order. Use the codec's render time,
        // not callback-delivery time, and do not turn backwards timestamps into gaps.
        val gap = if (lastRenderNs != 0L && renderedNs >= lastRenderNs) renderedNs - lastRenderNs else 0
        if (renderedNs > lastRenderNs) lastRenderNs = renderedNs
        if (entry?.stage != Stage.RELEASED) return Observed(null, gap)
        val timing = entry.timing ?: return Observed(null, gap)
        entry.timing = null
        entry.stage = Stage.COMPLETE
        return Observed(timing.takeIf { renderedNs >= it.releasedNs }, gap)
    }

    /** Even if a codec calls back for config/EOS output, it must not match a picture. */
    @Synchronized
    fun excludeOutput(ptsUs: Long): Int {
        if (!active) return 0
        var evicted = 0
        if (ptsUs !in entries && entries.size >= capacity) {
            val iterator = entries.entries.iterator()
            val removed = iterator.next().value
            if (removed.stage == Stage.INPUT || removed.stage == Stage.RELEASED) evicted = 1
            iterator.remove()
        }
        entries[ptsUs] = Entry(null, Stage.EXCLUDED)
        return evicted
    }

    @Synchronized
    fun invalidate() {
        active = false
        entries.clear()
    }

    @Synchronized
    fun size(): Int = entries.size
}
