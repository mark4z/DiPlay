package com.shilapi.xcertplay.browser

/** Optional encoded-video transport. No decoder, audio, data channel, or ownership authority. */
interface BrowserRtcPeer : AutoCloseable {
    interface Factory {
        val available: Boolean
        fun create(config: BrowserRtcConfig, listener: Listener): BrowserRtcPeer
    }
    interface Listener {
        fun offer(sdp: String)
        fun candidate(candidate: String, mid: String)
        fun state(state: String)
        fun requestKeyframe()
    }
    fun start()
    fun answer(sdp: String)
    fun candidate(candidate: String, mid: String)
    /** Queue acceptance only, never proof of packet/frame delivery. Must not block its caller. */
    fun send(frame: ByteArray, timestampUs: Long, key: Boolean): Boolean
    override fun close()
}

data class BrowserRtcConfig(val codec: String, val fmtp: String, val lanAddresses: List<String>)
