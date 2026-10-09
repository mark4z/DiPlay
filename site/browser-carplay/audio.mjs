import { AUDIO_TRANSPORT, MAX_AUDIO_CANDIDATES, positiveId, validAudioSdp, validAudioCandidate, preferOpusStereo } from './audio-protocol.mjs?v=webrtc-audio-v1';

const UPGRADE = 'Browser audio needs the WebRTC/Opus APK and viewer. Update both, or keep audio on Android.';
const FAILED = 'Browser audio stopped; Android audio restored.';
const STATS_INTERVAL = 250;
const STALL_TIMEOUT = 3000;
const SETUP_LABELS = Object.freeze({
  offer: 'Android did not send an audio offer',
  answer: 'the audio offer could not be answered',
  ice: 'the ICE connection did not connect',
  dtls: 'the encrypted audio connection did not finish',
  track: 'the browser did not receive an audio track',
  rtp: 'audio packets did not begin arriving',
  playback: 'the browser did not start playback; tap Audio again',
  acknowledgment: 'Android did not acknowledge playback readiness',
  active: 'audio had started',
});
const ANDROID_ERRORS = Object.freeze({
  'audio-offer-timeout': 'Android could not prepare its audio offer',
  'audio-answer-timeout': 'Android did not receive the browser audio answer',
  'audio-ice-timeout': 'Android could not establish the audio connection',
  'audio-capture-timeout': 'the Android application-audio callback did not start',
  'audio-no-local-candidates': 'Android found no usable audio network interface',
  'audio-pcm-init-failed': 'Android could not initialize application-audio export',
  'audio-pcm-start-failed': 'Android could not start application-audio export',
  'audio-unsupported-capture-format': 'Android supplied an unsupported application-audio format',
});

/** A receive-only WebRTC route. No microphone, capture, PCM queue, or auto-start. */
export class BrowserAudioPlayer {
  constructor({ sendMode = () => false, sendSignal = () => false, onState = () => {},
    PeerConnection = globalThis.RTCPeerConnection, MediaStream = globalThis.MediaStream,
    createAudio = () => globalThis.document.createElement('audio'),
    Receiver = globalThis.RTCRtpReceiver, secure = globalThis.isSecureContext,
    now = () => performance.now(),
    setTimer = (...args) => globalThis.setTimeout(...args), clearTimer = id => globalThis.clearTimeout(id) } = {}) {
    Object.assign(this, { sendMode, sendSignal, onState, PeerConnection, MediaStream, createAudio, Receiver, now, setTimer, clearTimer });
    this.supported = Boolean(secure && PeerConnection && MediaStream);
    this.requestId = 0; this.activeRequestId = 0; this.generation = 0; this.epoch = 0; this.lastEpoch = 0;
    this.enabled = this.pending = this.ready = this.disposed = false;
    this.diagnostics = { packetsReceived: null, jitterMs: null, concealedSamples: null, selectedCandidatePair: null };
    this.peer = this.audio = this.stream = this.track = null;
    this.handoffTimer = this.statsTimer = null;
    this.lastSetup = null;
    this.needsGesture = false; this.playAttempt = 0; this.playbackState = 'idle';
    this.remoteCandidates = []; this.remoteCandidateCount = this.localCandidateCount = this.remoteMessageCount = 0;
    this.addingCandidates = false; this.remoteSet = this.answered = this.playing = this.rtpProgress = false;
    this.lastPackets = null; this.lastProgressAt = this.lastAliveAt = this.alivePackets = 0;
  }

  emit(message, error = false) { this.onState({ enabled: this.enabled, pending: this.pending, ready: this.ready, needsGesture: this.needsGesture, message, error }); }
  getDiagnostics() {
    return { transport: AUDIO_TRANSPORT, state: this.enabled ? 'active' : this.pending ? 'starting' : 'native',
      setup: this.peer ? this.setupDiagnostics() : this.lastSetup ? { ...this.lastSetup } : null,
      ...this.diagnostics, selectedCandidatePair: this.diagnostics.selectedCandidatePair ? { ...this.diagnostics.selectedCandidatePair } : null };
  }
  setupDiagnostics() {
    const stage = this.enabled ? 'active' : this.ready ? 'acknowledgment' : !this.epoch ? 'offer' : !this.answered ? 'answer' :
      !['connected', 'completed'].includes(this.peer?.iceConnectionState) ? 'ice' : !this.connected() ? 'dtls' :
      !this.track ? 'track' : !this.rtpProgress ? 'rtp' : 'playback';
    const states = ['new', 'checking', 'connecting', 'connected', 'completed', 'disconnected', 'failed', 'closed'];
    return { stage, iceState: states.includes(this.peer?.iceConnectionState) ? this.peer.iceConnectionState : 'unknown',
      peerState: states.includes(this.peer?.connectionState) ? this.peer.connectionState : 'unknown',
      browserCandidates: Math.min(this.localCandidateCount, MAX_AUDIO_CANDIDATES),
      androidCandidates: Math.min(this.remoteCandidateCount, MAX_AUDIO_CANDIDATES),
      answerSent: this.answered, trackReceived: Boolean(this.track), playbackReady: this.playing,
      rtpProgress: this.rtpProgress,
      playback: { state: this.playbackState, paused: this.audio?.paused === true,
        readyState: Number.isInteger(this.audio?.readyState) ? this.audio.readyState : null,
        muted: this.audio?.muted === true, zeroVolume: this.audio?.volume === 0,
        trackMuted: this.track?.muted === true, trackEnded: this.track?.readyState === 'ended',
        rtpFresh: this.rtpProgress && this.now() - this.lastProgressAt < STALL_TIMEOUT } };
  }
  timeoutMessage(code = null) {
    const setup = this.setupDiagnostics();
    const playbackReason = this.needsGesture ? 'the browser needs another tap on Play audio here' :
      this.rtpProgress && !setup.playback.rtpFresh ? 'audio packets stopped before playback was ready' :
      setup.playback.trackEnded ? 'the audio track ended before playback was ready' :
      setup.playback.muted || setup.playback.zeroVolume ? 'the browser audio output was muted' : SETUP_LABELS.playback;
    const reason = Object.hasOwn(ANDROID_ERRORS, code) ? ANDROID_ERRORS[code] :
      setup.stage === 'playback' ? playbackReason : SETUP_LABELS[setup.stage];
    const outcome = code && !code.endsWith('-timeout') ? 'failed' : 'timed out';
    return `Audio setup ${outcome}: ${reason}. ICE candidates: browser ${setup.browserCandidates}, Android ${setup.androidCandidates}. Audio stays on Android.`;
  }
  current(generation) { return generation === this.generation && !this.disposed && Boolean(this.peer); }
  connected() {
    return ['connected', 'completed'].includes(this.peer?.iceConnectionState) &&
      (!this.peer?.connectionState || this.peer.connectionState === 'connected');
  }
  signal(type, fields = {}) {
    return this.sendSignal({ type, requestId: this.activeRequestId, epoch: this.epoch, transport: AUDIO_TRANSPORT, ...fields });
  }

  async enableFromGesture({ test = false } = {}) {
    if (this.disposed || this.enabled || this.pending) return false;
    if (!this.supported) { this.emit('Browser audio needs secure WebRTC/Opus support. Audio stays on Android.', true); return false; }
    if (!positiveId(this.requestId + 1)) { this.emit('Audio request limit reached. Reload this page.', true); return false; }
    const generation = ++this.generation;
    this.activeRequestId = ++this.requestId;
    this.test = test === true;
    this.lastSetup = null;
    this.diagnostics = { packetsReceived: null, jitterMs: null, concealedSamples: null, selectedCandidatePair: null };
    this.pending = true; this.emit('Starting WebRTC browser audio…');
    try {
      const peer = this.peer = new this.PeerConnection({ iceServers: [], bundlePolicy: 'max-bundle', rtcpMuxPolicy: 'require' });
      const transceiver = peer.addTransceiver('audio', { direction: 'recvonly' });
      const opus = this.Receiver?.getCapabilities?.('audio')?.codecs?.filter(codec => codec.mimeType?.toLowerCase() === 'audio/opus');
      if (opus?.length && transceiver.setCodecPreferences) transceiver.setCodecPreferences(opus);
      const stream = this.stream = new this.MediaStream();
      const audio = this.audio = this.createAudio();
      audio.autoplay = true; audio.playsInline = true; audio.muted = false; audio.volume = 1;
      audio.srcObject = stream;
      audio.onpause = audio.onerror = audio.onended = () => { if (this.current(generation)) this.disable(FAILED, true); };
      // Call play synchronously inside the user's click, against the same stream
      // that receives the track. Its promise may remain pending until RTP arrives.
      this.attemptPlayback(generation);
      if (!this.current(generation)) return false;
      peer.ontrack = ({ track }) => {
        if (!this.current(generation)) { track?.stop(); return; }
        if (!track || track.kind !== 'audio' || this.track) { this.disable(FAILED, true); return; }
        this.track = track;
        track.onended = () => { if (this.current(generation)) this.disable(FAILED, true); };
        stream.addTrack(track);
        // Some media elements do not resume the initial empty stream's play
        // request. Retry once with a track, without assuming user activation.
        if (!this.playing) this.attemptPlayback(generation);
        this.maybeReady();
      };
      peer.onicecandidate = ({ candidate }) => {
        if (!this.current(generation) || !candidate || !this.epoch) return;
        // A non-null empty candidate is the standard end-of-generation marker.
        if (candidate.candidate === '') return;
        const data = { candidate: candidate.candidate, sdpMid: candidate.sdpMid, sdpMLineIndex: candidate.sdpMLineIndex };
        // Validate signaling syntax; ICE and DTLS authenticate the selected path.
        if (!validAudioCandidate(data)) return;
        if (++this.localCandidateCount > MAX_AUDIO_CANDIDATES || !this.signal('audioIce', data)) this.disable(FAILED, true);
      };
      peer.oniceconnectionstatechange = peer.onconnectionstatechange = () => {
        if (!this.current(generation)) return;
        if (['failed', 'closed', 'disconnected'].includes(peer.iceConnectionState) || ['failed', 'closed', 'disconnected'].includes(peer.connectionState)) this.disable(FAILED, true);
        else this.maybeReady();
      };
      if (!this.sendMode(true, this.activeRequestId, this.test ? 'test' : undefined)) throw new Error('connection');
      this.handoffTimer = this.setTimer(() => {
        if (this.current(generation) && this.pending) this.disable(this.timeoutMessage(), true);
      }, 15000);
      this.scheduleStats(generation);
      this.emit('Waiting for the Android WebRTC audio offer…');
      return true;
    } catch {
      if (generation === this.generation) this.disable('Browser audio could not start. Connect to DiPlay or keep audio on Android.', true);
      return false;
    }
  }

  attemptPlayback(generation) {
    if (!this.current(generation)) return;
    const audio = this.audio, attempt = ++this.playAttempt;
    this.playbackState = 'pending'; this.needsGesture = false;
    let playback;
    try { playback = audio.play(); } catch (error) { this.playbackFailed(error, generation, attempt); return; }
    Promise.resolve(playback).then(() => {
      if (!this.current(generation) || attempt !== this.playAttempt) return;
      this.playing = !audio.paused;
      this.playbackState = this.playing ? 'playing' : 'paused';
      if (!this.playing) { this.requirePlaybackGesture(); return; }
      this.needsGesture = false;
      this.maybeReady();
    }, error => this.playbackFailed(error, generation, attempt));
  }

  playbackFailed(error, generation, attempt) {
    if (!this.current(generation) || attempt !== this.playAttempt) return;
    if (error?.name === 'NotAllowedError') { this.requirePlaybackGesture(); return; }
    this.playbackState = 'failed';
    this.disable('Playback was blocked. Tap Play audio here again; audio stays on Android.', true);
  }

  requirePlaybackGesture() {
    this.playing = false; this.needsGesture = true; this.playbackState = 'gesture-required';
    this.emit('Tap Play audio here to allow browser playback. Audio stays on Android until playback starts.', true);
  }

  resumePlaybackFromGesture() {
    if (!this.pending || this.ready || !this.needsGesture || !this.peer || this.disposed) return false;
    // Keep this call synchronous with the click, on the existing populated stream.
    // No new route, ICE negotiation, or deadline extension is permitted.
    const generation = this.generation;
    this.attemptPlayback(generation);
    if (!this.current(generation)) return false;
    if (!this.needsGesture) this.emit('Starting browser playback…');
    return true;
  }

  handleMessage(message) {
    if (this.disposed || !message || (!this.pending && !this.enabled)) return;
    if (message.requestId !== this.activeRequestId) {
      // A legacy enabled ACK without a request ID must never switch this route.
      if (message.type === 'audioState' && message.enabled && message.requestId === undefined) this.disable(UPGRADE, true);
      return;
    }
    if (++this.remoteMessageCount > 64) { this.disable('Too many audio control messages; audio stays on Android.', true); return; }
    if (message.type !== 'audioState' && message.transport !== AUDIO_TRANSPORT) { this.disable(UPGRADE, true); return; }
    if (message.type === 'audioOffer') {
      if (!this.pending || this.epoch) return; // no renegotiation or duplicate work
      if (!positiveId(message.epoch) || message.epoch <= this.lastEpoch || !validAudioSdp(message.sdp, 'sendonly')) { this.disable(UPGRADE, true); return; }
      this.epoch = this.lastEpoch = message.epoch;
      this.remoteCandidates = this.remoteCandidates.filter(candidate => candidate.epoch === this.epoch);
      void this.answerOffer(message.sdp, this.generation);
    } else if (message.type === 'audioIce') {
      if (!positiveId(message.epoch) || (this.epoch && message.epoch !== this.epoch)) return;
      if (++this.remoteCandidateCount > MAX_AUDIO_CANDIDATES || !validAudioCandidate(message)) { this.disable('Invalid WebRTC audio signaling; audio stays on Android.', true); return; }
      this.remoteCandidates.push({ candidate: message.candidate, sdpMid: message.sdpMid, sdpMLineIndex: message.sdpMLineIndex, epoch: message.epoch });
      void this.drainCandidates(this.generation);
    } else if (message.type === 'audioState') {
      if (typeof message.enabled !== 'boolean' || !positiveId(message.epoch)) { this.disable(UPGRADE, true); return; }
      if (!message.enabled) {
        // Setup can fail before an offer assigns the epoch. Otherwise only a
        // matching route may revoke playback; old failures cannot stop a new one.
        if (this.epoch && message.epoch !== this.epoch) return;
        const failure = message.code === 'test-complete' ? 'Audio test finished. Audio plays on Android.' : message.code === 'audio-source-unavailable' ? 'This Android source cannot export audio. Audio stays on Android.' :
          Object.hasOwn(ANDROID_ERRORS, message.code) || ['audio-handoff-timeout', 'audio-readiness-timeout'].includes(message.code) ? this.timeoutMessage(message.code) :
          message.code ? 'WebRTC audio is unavailable. Audio stays on Android.' : 'Audio plays on Android.';
        this.closeAudio();
        this.emit(failure, Boolean(message.code && message.code !== 'test-complete'));
        return;
      }
      if (message.transport !== AUDIO_TRANSPORT || !this.epoch) { this.disable(UPGRADE, true); return; }
      if (message.epoch !== this.epoch) return;
      if (!this.ready || !this.connected() || !this.playing) { this.disable('Premature audio handoff; audio stays on Android.', true); return; }
      if (this.enabled) return;
      this.cancelHandoffTimer();
      this.enabled = true; this.pending = false;
      this.lastAliveAt = this.now(); this.alivePackets = this.lastPackets;
      this.emit(this.test ? 'Playing the short WebRTC audio test. Android audio stays on.' : 'Audio plays here through WebRTC/Opus. Android output is muted.');
    } else if (message.type === 'audioError') {
      if (!this.epoch || message.epoch === this.epoch) this.disable(FAILED, true);
    } else if (message.type === 'audioStopped' && message.epoch === this.epoch) {
      this.disable(UPGRADE, true);
    }
  }

  async answerOffer(sdp, generation) {
    const peer = this.peer;
    try {
      await peer.setRemoteDescription({ type: 'offer', sdp });
      if (!this.current(generation)) return;
      this.remoteSet = true;
      await this.drainCandidates(generation);
      if (!this.current(generation)) return;
      const answer = await peer.createAnswer();
      if (!this.current(generation)) return;
      if (!validAudioSdp(answer.sdp, 'recvonly')) throw new Error('answer');
      answer.sdp = preferOpusStereo(answer.sdp);
      if (!validAudioSdp(answer.sdp, 'recvonly')) throw new Error('answer');
      await peer.setLocalDescription(answer);
      if (!this.current(generation)) return;
      if (!this.signal('audioAnswer', { sdp: answer.sdp })) throw new Error('send');
      this.answered = true;
      this.emit('Connecting encrypted WebRTC audio…');
      this.maybeReady();
    } catch { if (this.current(generation)) this.disable(FAILED, true); }
  }

  async drainCandidates(generation) {
    if (!this.current(generation) || !this.remoteSet || this.addingCandidates) return;
    this.addingCandidates = true;
    const peer = this.peer;
    try {
      while (this.current(generation) && this.remoteCandidates.length) {
        const { epoch, ...candidate } = this.remoteCandidates.shift();
        if (epoch === this.epoch) await peer.addIceCandidate(candidate);
      }
    } catch { if (this.current(generation)) this.disable(FAILED, true); }
    finally { if (this.current(generation)) this.addingCandidates = false; }
  }

  scheduleStats(generation) {
    if (!this.current(generation)) return;
    this.statsTimer = this.setTimer(() => { this.statsTimer = null; void this.readStats(generation); }, STATS_INTERVAL);
  }

  async readStats(generation) {
    const peer = this.peer;
    try {
      const stats = await peer.getStats();
      if (!this.current(generation)) return;
      let packets = 0, found = false, jitterMs = null, concealedSamples = null, selectedPair = null;
      stats.forEach(report => {
        if (report.type === 'inbound-rtp' && (report.kind === 'audio' || report.mediaType === 'audio') && !report.isRemote &&
            Number.isSafeInteger(report.packetsReceived) && report.packetsReceived >= 0) {
          packets += report.packetsReceived; found = true;
          if (Number.isFinite(report.jitter) && report.jitter >= 0) jitterMs = Math.round(Math.min(report.jitter * 1000, 60000));
          if (Number.isSafeInteger(report.concealedSamples) && report.concealedSamples >= 0) concealedSamples = report.concealedSamples;
        }
        if (report.type === 'transport' && typeof report.selectedCandidatePairId === 'string') {
          const pair = stats.get(report.selectedCandidatePairId);
          if (pair) selectedPair = pair;
        }
        else if (!selectedPair && report.type === 'candidate-pair' && report.nominated && report.state === 'succeeded') selectedPair = report;
      });
      const local = selectedPair && stats.get(selectedPair.localCandidateId), remote = selectedPair && stats.get(selectedPair.remoteCandidateId);
      const candidateType = value => ['host', 'srflx', 'prflx', 'relay'].includes(value) ? value : null;
      this.diagnostics = { packetsReceived: found && Number.isSafeInteger(packets) ? packets : null, jitterMs, concealedSamples,
        selectedCandidatePair: selectedPair ? { localType: candidateType(local?.candidateType), remoteType: candidateType(remote?.candidateType),
          protocol: ['udp', 'tcp'].includes(local?.protocol) ? local.protocol : null } : null };
      const now = this.now();
      if (found && Number.isSafeInteger(packets)) {
        if (this.lastPackets !== null && packets > this.lastPackets) { this.rtpProgress = true; this.lastProgressAt = now; }
        else if (this.lastPackets !== null && packets < this.lastPackets) { this.disable(FAILED, true); return; }
        this.lastPackets = packets;
      }
      if (this.ready && (this.audio?.paused || this.audio?.muted || this.audio?.volume === 0 || this.track?.readyState === 'ended' || now - this.lastProgressAt >= STALL_TIMEOUT)) {
        this.disable('Browser audio stalled; Android audio restored.', true); return;
      }
      this.maybeReady();
      if (this.enabled && this.connected() && this.playing && now - this.lastAliveAt >= 1000 && this.lastPackets > this.alivePackets) {
        if (!this.signal('audioAlive')) { this.disable(FAILED, true); return; }
        this.lastAliveAt = now; this.alivePackets = this.lastPackets;
      }
    } catch { if (this.current(generation)) { this.disable(FAILED, true); return; } }
    this.scheduleStats(generation);
  }

  maybeReady() {
    if (!this.pending || this.ready || !this.answered || !this.connected() || !this.track || this.track.readyState === 'ended' ||
        !this.playing || this.audio?.paused || this.audio?.muted || this.audio?.volume === 0 || !this.rtpProgress || this.now() - this.lastProgressAt >= STALL_TIMEOUT) return;
    this.ready = true;
    if (!this.signal('audioReady')) { this.disable(FAILED, true); return; }
    this.cancelHandoffTimer();
    const generation = this.generation;
    this.handoffTimer = this.setTimer(() => {
      if (this.current(generation) && this.pending) this.disable(this.timeoutMessage(), true);
    }, 5000);
    this.emit('Audio is receiving. Waiting for Android to confirm the handoff…');
  }

  // Kind-3 packets identify an old APK. They are never decoded or buffered.
  handlePacket() {
    if (this.pending || this.enabled) this.disable(UPGRADE, true);
    return false;
  }
  disable(message = 'Audio plays on Android.', error = false) {
    const wasRequested = this.pending || this.ready || this.enabled;
    const requestId = this.activeRequestId;
    this.closeAudio();
    if (wasRequested) this.sendMode(false, requestId);
    this.emit(message, error);
  }
  reset(message = 'Audio plays on Android.') { this.closeAudio(); this.activeRequestId = 0; this.lastEpoch = 0; this.emit(message); }
  cancelHandoffTimer() {
    if (this.handoffTimer !== null) this.clearTimer(this.handoffTimer);
    this.handoffTimer = null;
  }
  closeAudio() {
    if (this.peer) this.lastSetup = this.setupDiagnostics();
    this.cancelHandoffTimer();
    if (this.statsTimer !== null) this.clearTimer(this.statsTimer);
    this.statsTimer = null;
    ++this.generation; ++this.playAttempt; this.needsGesture = false; this.playbackState = 'idle';
    this.enabled = this.pending = this.ready = false; this.epoch = 0;
    const peer = this.peer, audio = this.audio, track = this.track;
    this.peer = this.audio = this.stream = this.track = null;
    this.remoteCandidates = []; this.remoteCandidateCount = this.localCandidateCount = this.remoteMessageCount = 0;
    this.addingCandidates = false; this.remoteSet = this.answered = this.playing = this.rtpProgress = false;
    this.lastPackets = null; this.lastProgressAt = this.lastAliveAt = this.alivePackets = 0;
    if (track) { track.onended = null; track.stop(); }
    if (audio) { audio.onpause = audio.onerror = audio.onended = null; audio.pause(); audio.srcObject = null; }
    if (peer) { peer.ontrack = peer.onicecandidate = peer.oniceconnectionstatechange = peer.onconnectionstatechange = null; peer.close(); }
  }
  dispose() { this.disable(); this.disposed = true; }
}

