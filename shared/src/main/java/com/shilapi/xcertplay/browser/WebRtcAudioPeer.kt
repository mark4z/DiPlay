package com.shilapi.xcertplay.browser

import android.content.Context
import android.media.AudioFormat
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.AudioProcessingOptions
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal interface BrowserAudioPeer {
    fun start()
    fun answer(sdp: String)
    fun addIce(candidate: String, mid: String?, index: Int)
    fun close()
}

internal interface BrowserAudioPeerCallbacks {
    fun offer(sdp: String)
    fun ice(candidate: String, mid: String?, index: Int)
    fun connected(connected: Boolean)
    fun failed(code: String)
}

/**
 * One send-only Opus connection with normal ICE path selection.
 * Captures decoded application PCM, NEVER a microphone.
 * Native operations and signaling callbacks have one bounded serial owner. PCM runs on WebRTC's
 * capture thread and must not block on signaling, native rendering, or network writes.
 */
internal class WebRtcAudioPeer(
    context: Context,
    private val fill: (ByteBuffer) -> Unit,
    private val callbacks: BrowserAudioPeerCallbacks,
    private val embeddedHttps: Boolean = false,
) : BrowserAudioPeer {
    private val context = context.applicationContext
    private val lifecycleLock = Any()
    private val closed = AtomicBoolean()
    private val started = AtomicBoolean()
    private val pacer = WebRtcAudioPacer()
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(64), { task ->
            Thread(task, "CarPlay-WebRTC-audio").apply { isDaemon = true }
        })
    // All fields below belong to worker, including partial-initialization cleanup.
    private var device: JavaAudioDeviceModule? = null
    private var factory: PeerConnectionFactory? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var peer: PeerConnection? = null
    private var localDescriptionSet = false
    private var answerStarted = false
    private var answerSet = false
    private var localCandidates = 0
    private var remoteCandidates = 0
    private val pendingIce = ArrayList<IceCandidate>()

    override fun start() {
        if (started.compareAndSet(false, true)) dispatch { initialize() }
    }

    override fun answer(sdp: String) {
        if (!WebRtcAudioRules.isReceiveOnlyAnswer(sdp)) {
            terminate("audio-invalid-answer")
            return
        }
        dispatch {
            val connection = peer
            if (connection == null || !localDescriptionSet || answerStarted) {
                terminate("audio-invalid-answer")
            } else {
                answerStarted = true
                connection.setRemoteDescription(object : DescriptionObserver() {
                    override fun onSetSuccess() = dispatch {
                        answerSet = true
                        for (candidate in pendingIce) addCandidate(candidate)
                        pendingIce.clear()
                    }
                }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
            }
        }
    }

    override fun addIce(candidate: String, mid: String?, index: Int) {
        if (!WebRtcAudioRules.isValidCandidate(candidate) || index != 0 ||
            !WebRtcAudioRules.isValidCandidateMid(mid)) {
            terminate("audio-invalid-candidate")
            return
        }
        dispatch {
            if (++remoteCandidates > WebRtcAudioRules.MAX_CANDIDATES) {
                terminate("audio-too-many-candidates")
            } else {
                val value = IceCandidate(mid, index, candidate)
                if (answerSet) addCandidate(value) else pendingIce.add(value)
            }
        }
    }

    override fun close() = terminate(null)

    private fun initialize() {
        initializeFactory(context)
        val module = JavaAudioDeviceModule.builder(context)
            .setInputSampleRate(WebRtcAudioRules.SAMPLE_RATE)
            .setUseStereoInput(true)
            .setAudioFormat(AudioFormat.ENCODING_PCM_16BIT)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setEnableVolumeLogger(false)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) = terminate("audio-pcm-init-failed")
                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode, errorMessage: String,
                ) = terminate("audio-pcm-start-failed")
                override fun onWebRtcAudioRecordError(errorMessage: String) = terminate("audio-pcm-failed")
            })
            .setAudioBufferCallback { buffer, format, channels, sampleRate, _, _ ->
                capture(buffer, format, channels, sampleRate)
            }
            .createAudioDeviceModule()
        device = module
        // This MUST precede factory/native ADM initialization. Muting an actual AudioRecord does
        // not provide this guarantee. Do not call prewarmRecording/requestStartRecording.
        module.setAudioRecordEnabled(false)
        module.setSpeakerMute(true)
        val builder = PeerConnectionFactory.builder().setAudioDeviceModule(module)
        WebRtcAudioNetworkPolicy.factoryOptions(embeddedHttps)?.let { builder.setOptions(it) }
        val rtcFactory = builder
            .createPeerConnectionFactory()
        factory = rtcFactory
        val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            iceCandidatePoolSize = 0
        }
        val connection = checkNotNull(rtcFactory.createPeerConnection(configuration, observer))
        peer = connection
        connection.setAudioPlayout(false)
        val constraints = MediaConstraints().apply {
            for (key in listOf("googEchoCancellation", "googAutoGainControl", "googNoiseSuppression",
                "googHighpassFilter", "googTypingNoiseDetection")) {
                mandatory.add(MediaConstraints.KeyValuePair(key, "false"))
            }
        }
        val audioSource = rtcFactory.createAudioSource(constraints)
        source = audioSource
        val audioTrack = rtcFactory.createAudioTrack("diplay-audio", audioSource)
        track = audioTrack
        check(audioTrack.setAudioProcessingOptions(AudioProcessingOptions.raw()).isSuccess)
        val encoding = RtpParameters.Encoding("", true, null).apply {
            maxBitrateBps = 128_000
            adaptiveAudioPacketTime = false
        }
        val transceiver = connection.addTransceiver(audioTrack, RtpTransceiver.RtpTransceiverInit(
            RtpTransceiver.RtpTransceiverDirection.SEND_ONLY, listOf("diplay"), listOf(encoding)))
        val opus = rtcFactory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
            .codecs.filter { it.name.equals("opus", ignoreCase = true) && it.clockRate == WebRtcAudioRules.SAMPLE_RATE }
        check(opus.isNotEmpty())
        check(transceiver.setCodecPreferences(opus).isSuccess)
        connection.createOffer(object : DescriptionObserver() {
            override fun onCreateSuccess(description: SessionDescription) = dispatch {
                // createOffer normally precedes gathering, but also fail closed if a future
                // dependency ever embeds malformed signaling into that returned SDP.
                if (!WebRtcAudioRules.isSendOnlyOffer(description.description)) {
                    terminate("audio-invalid-offer")
                } else {
                    connection.setLocalDescription(object : DescriptionObserver() {
                        override fun onSetSuccess() = dispatch {
                            localDescriptionSet = true
                            callbacks.offer(description.description)
                        }
                    }, description)
                }
            }
        }, MediaConstraints())
    }

    private fun capture(buffer: ByteBuffer, format: Int, channels: Int, sampleRate: Int): Long {
        // Continue pacing silence during teardown, until native stops its callback loop. Returning
        // immediately whenever closed would turn the disabled-recording loop into a CPU hot spin.
        val timestamp = try { pacer.awaitTick() } catch (_: InterruptedException) { System.nanoTime() }
        if (format != AudioFormat.ENCODING_PCM_16BIT || channels != WebRtcAudioRules.CHANNELS ||
            sampleRate != WebRtcAudioRules.SAMPLE_RATE || buffer.capacity() != WebRtcAudioRules.FRAME_BYTES) {
            buffer.clear()
            while (buffer.hasRemaining()) buffer.put(0.toByte())
            buffer.clear()
            terminate("audio-unsupported-capture-format")
            return timestamp
        }
        try {
            WebRtcAudioRules.writePcm(buffer) { if (!closed.get()) fill(it) }
        } catch (_: Exception) {
            terminate("audio-pcm-failed")
        }
        return timestamp
    }

    private fun addCandidate(candidate: IceCandidate) {
        if (peer?.addIceCandidate(candidate) != true) terminate("audio-candidate-failed")
    }

    private val observer = object : PeerConnection.Observer {
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) = dispatch {
            when (state) {
                PeerConnection.PeerConnectionState.CONNECTED -> callbacks.connected(true)
                PeerConnection.PeerConnectionState.DISCONNECTED -> callbacks.connected(false)
                PeerConnection.PeerConnectionState.FAILED -> terminate("audio-peer-failed")
                PeerConnection.PeerConnectionState.CLOSED -> terminate("audio-peer-closed")
                else -> Unit
            }
        }
        override fun onIceCandidate(candidate: IceCandidate) = dispatch {
            if (WebRtcAudioRules.isValidCandidate(candidate.sdp) && candidate.sdpMLineIndex == 0 &&
                WebRtcAudioRules.isValidCandidateMid(candidate.sdpMid) &&
                localCandidates < WebRtcAudioRules.MAX_CANDIDATES) {
                localCandidates++
                callbacks.ice(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
            }
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = dispatch {
            if (state == PeerConnection.IceGatheringState.COMPLETE && localCandidates == 0) {
                terminate("audio-no-local-candidates")
            }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = terminate("audio-unexpected-data-channel")
        override fun onRenegotiationNeeded() = Unit
    }

    private open inner class DescriptionObserver : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = terminate("audio-offer-failed")
        override fun onSetFailure(error: String) = terminate("audio-description-failed")
    }

    private fun dispatch(action: () -> Unit) {
        synchronized(lifecycleLock) {
            if (closed.get()) return
            try {
                worker.execute {
                    if (!closed.get()) try { action() } catch (_: Exception) {
                        terminate("audio-peer-failed")
                    } catch (_: LinkageError) {
                        terminate("audio-webrtc-unavailable")
                    }
                }
            } catch (_: RejectedExecutionException) {
                terminate("audio-signaling-overflow")
            }
        }
    }

    private fun terminate(code: String?) {
        synchronized(lifecycleLock) {
            if (!closed.compareAndSet(false, true)) return
            worker.queue.clear()
            // Cleanup must never run on WebRTC's capture/signaling thread, or dispose could join
            // the calling native thread. Clearing the bounded queue reserves this final slot.
            worker.execute {
                if (code != null) runCatching { callbacks.failed(code) }
                pendingIce.clear()
                runCatching { peer?.dispose() }; peer = null
                runCatching { track?.dispose() }; track = null
                runCatching { source?.dispose() }; source = null
                runCatching { factory?.dispose() }; factory = null
                runCatching { device?.release() }; device = null
            }
            worker.shutdown()
        }
    }

    companion object {
        private val factoryLock = Any()
        private var factoryInitialized = false
        private fun initializeFactory(context: Context) = synchronized(factoryLock) {
            if (!factoryInitialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions
                    .builder(context).createInitializationOptions())
                factoryInitialized = true
            }
        }
    }
}


