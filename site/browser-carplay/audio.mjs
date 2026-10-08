import { AUDIO_TRANSPORT, MAX_AUDIO_CANDIDATES, positiveId, validAudioSdp, validAudioCandidate, preferOpusStereo, localIceAddress, iceAddressClass } from './audio-protocol.mjs?v=webrtc-audio-v1';

const UPGRADE = 'Browser audio needs the WebRTC/Opus APK and viewer. Update both, or keep audio on Android.';
const FAILED = 'Browser audio stopped; Android audio restored.';
const STATS_INTERVAL = 250;
const STALL_TIMEOUT = 3000;
const SETUP_LABELS = Object.freeze({
  offer: 'Android did not send an audio offer',
  answer: 'the audio offer could not be answered',
  ice: 'the direct local ICE connection did not connect',
  dtls: 'the encrypted audio connection did not finish',
  track: 'the browser did not receive an audio track',
  rtp: 'audio packets did not begin arriving',
  pair: 'the browser could not verify the selected local ICE pair',
  playback: 'the browser did not start playback; tap Audio again',
  acknowledgment: 'Android did not acknowledge playback readiness',
  active: 'audio had started',
});
const ANDROID_ERRORS = Object.freeze({
  'audio-offer-timeout': 'Android could not prepare its audio offer',
  'audio-answer-timeout': 'Android did not receive the browser audio answer',
  'audio-ice-timeout': 'Android could not establish the direct local audio connection',
  'audio-capture-timeout': 'the Android application-audio callback did not start',
  'audio-no-local-candidates': 'Android found no usable local audio network interface',
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
    this.remoteCandidates = []; this.remoteCandidateCount = this.localCandidateCount = this.remoteMessageCount = 0;
    this.addingCandidates = false; this.remoteSet = this.answered = this.playing = this.rtpProgress = this.localPairVerified = false;
    this.lastPackets = null; this.lastProgressAt = this.lastAliveAt = this.alivePackets = 0;
  }

  emit(message, error = false) { this.onState({ enabled: this.enabled, pending: this.pending, ready: this.ready, message, error }); }
  getDiagnostics() {
    return { transport: AUDIO_TRANSPORT, state: this.enabled ? 'active' : this.pending ? 'starting' : 'native',
      setup: this.peer ? this.setupDiagnostics() : this.lastSetup ? { ...this.lastSetup } : null,
      ...this.diagnostics, selectedCandidatePair: this.diagnostics.selectedCandidatePair ? { ...this.diagnostics.selectedCandidatePair } : null };
  }
  setupDiagnostics() {
    const stage = this.enabled ? 'active' : this.ready ? 'acknowledgment' : !this.epoch ? 'offer' : !this.answered ? 'answer' :
      !this.connected() ? 'ice' : this.peer?.connectionState && this.peer.connectionState !== 'connected' ? 'dtls' :
      !this.track ? 'track' : !this.rtpProgress ? 'rtp' : !this.localPairVerified ? 'pair' : 'playback';
    const states = ['new', 'checking', 'connecting', 'connected', 'completed', 'disconnected', 'failed', 'closed'];
    return { stage, iceState: states.includes(this.peer?.iceConnectionState) ? this.peer.iceConnectionState : 'unknown',
      peerState: states.includes(this.peer?.connectionState) ? this.peer.connectionState : 'unknown',
      browserCandidates: Math.min(this.localCandidateCount, MAX_AUDIO_CANDIDATES),
      androidCandidates: Math.min(this.remoteCandidateCount, MAX_AUDIO_CANDIDATES),
      answerSent: this.answered, trackReceived: Boolean(this.track), playbackReady: this.playing,
      localPairVerified: this.localPairVerified, rtpProgress: this.rtpProgress };
  }
  timeoutMessage(code = null) {
    const setup = this.setupDiagnostics();
    const reason = Object.hasOwn(ANDROID_ERRORS, code) ? ANDROID_ERRORS[code] : SETUP_LABELS[setup.stage];
    const outcome = code && !code.endsWith('-timeout') ? 'failed' : 'timed out';
    return `Audio setup ${outcome}: ${reason}. ICE candidates: browser ${setup.browserCandidates}, Android ${setup.androidCandidates}. Audio stays on Android.`;
  }
  current(generation) { return generation === this.generation && !this.disposed && Boolean(this.peer); }
  connected() { return ['connected', 'completed'].includes(this.peer?.iceConnectionState); }
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
      const playback = audio.play();
      Promise.resolve(playback).then(() => {
        if (!this.current(generation)) return;
        this.playing = !audio.paused;
        if (!this.playing) this.disable('Tap Play audio here again to allow playback. Audio stays on Android.', true);
        else this.maybeReady();
      }, () => { if (this.current(generation)) this.disable('Playback was blocked. Tap Play audio here again; audio stays on Android.', true); });
      peer.ontrack = ({ track }) => {
        if (!this.current(generation)) { track?.stop(); return; }
        if (!track || track.kind !== 'audio' || this.track) { this.disable(FAILED, true); return; }
        this.track = track;
        track.onended = () => { if (this.current(generation)) this.disable(FAILED, true); };
        stream.addTrack(track);
        this.maybeReady();
      };
      peer.onicecandidate = ({ candidate }) => {
        if (!this.current(generation) || !candidate || !this.epoch) return;
        const data = { candidate: candidate.candidate, sdpMid: candidate.sdpMid, sdpMLineIndex: candidate.sdpMLineIndex };
        // A machine can have unrelated public interfaces. Do not signal those
        // candidates and do not turn their discovery into an audio failure.
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
        if (report.type === 'transport' && typeof report.selectedCandidatePairId === 'string') selectedPair = stats.get(report.selectedCandidatePairId) || selectedPair;
        else if (!selectedPair && report.type === 'candidate-pair' && report.nominated && report.state === 'succeeded') selectedPair = report;
      });
      const local = selectedPair && stats.get(selectedPair.localCandidateId), remote = selectedPair && stats.get(selectedPair.remoteCandidateId);
      const localPairMetadataVerified = Boolean(local?.candidateType === 'host' && remote?.candidateType === 'host' &&
        ['udp', 'tcp'].includes(local?.protocol) && local.protocol === remote?.protocol &&
        typeof local.address === 'string' && localIceAddress(local.address) && typeof remote.address === 'string' && localIceAddress(remote.address));
      this.localPairVerified = selectedPair?.state === 'succeeded' && localPairMetadataVerified;
      // A transport can reference a pair before connectivity checks finish. Wait
      // only during setup and only when every host/local/protocol check passes.
      // The original setup deadline remains in force; this never grants readiness.
      const pairChecking = !this.ready && localPairMetadataVerified &&
        ['frozen', 'waiting', 'in-progress'].includes(selectedPair?.state);
      const candidateType = value => ['host', 'srflx', 'prflx', 'relay'].includes(value) ? value : null;
      this.diagnostics = { packetsReceived: found && Number.isSafeInteger(packets) ? packets : null, jitterMs, concealedSamples,
        selectedCandidatePair: selectedPair ? { localType: candidateType(local?.candidateType), remoteType: candidateType(remote?.candidateType),
          protocol: ['udp', 'tcp'].includes(local?.protocol) ? local.protocol : null } : null };
      if ((selectedPair || this.ready) && !this.localPairVerified && !pairChecking) {
        // Fixed labels preserve the cause without exposing addresses, SDP, or raw stats.
        const reason = !selectedPair ? 'selected pair missing' : !local || !remote ? 'candidate details missing' :
          local.candidateType !== 'host' || remote.candidateType !== 'host' ? 'candidate type is not host' :
          !['udp', 'tcp'].includes(local.protocol) || local.protocol !== remote.protocol ? 'candidate protocol is missing or mismatched' :
          typeof local.address !== 'string' || typeof remote.address !== 'string' ? 'candidate address unavailable' :
          !localIceAddress(local.address) || !localIceAddress(remote.address) ?
            `candidate address is outside local policy; browser=${iceAddressClass(local.address)}, Android=${iceAddressClass(remote.address)}` :
          'selected pair has not succeeded';
        this.disable(`Could not verify a local-only WebRTC audio connection (${reason}). Audio stays on Android.`, true); return;
      }
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
      if (this.enabled && this.connected() && this.localPairVerified && this.playing && now - this.lastAliveAt >= 1000 && this.lastPackets > this.alivePackets) {
        if (!this.signal('audioAlive')) { this.disable(FAILED, true); return; }
        this.lastAliveAt = now; this.alivePackets = this.lastPackets;
      }
    } catch { if (this.current(generation)) { this.disable(FAILED, true); return; } }
    this.scheduleStats(generation);
  }

  maybeReady() {
    if (!this.pending || this.ready || !this.answered || !this.connected() || !this.track || this.track.readyState === 'ended' ||
        !this.localPairVerified || !this.playing || this.audio?.paused || this.audio?.muted || this.audio?.volume === 0 || !this.rtpProgress || this.now() - this.lastProgressAt >= STALL_TIMEOUT) return;
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
    ++this.generation; this.enabled = this.pending = this.ready = false; this.epoch = 0;
    const peer = this.peer, audio = this.audio, track = this.track;
    this.peer = this.audio = this.stream = this.track = null;
    this.remoteCandidates = []; this.remoteCandidateCount = this.localCandidateCount = this.remoteMessageCount = 0;
    this.addingCandidates = false; this.remoteSet = this.answered = this.playing = this.rtpProgress = this.localPairVerified = false;
    this.lastPackets = null; this.lastProgressAt = this.lastAliveAt = this.alivePackets = 0;
    if (track) { track.onended = null; track.stop(); }
    if (audio) { audio.onpause = audio.onerror = audio.onended = null; audio.pause(); audio.srcObject = null; }
    if (peer) { peer.ontrack = peer.onicecandidate = peer.oniceconnectionstatechange = peer.onconnectionstatechange = null; peer.close(); }
  }
  dispose() { this.disable(); this.disposed = true; }
}

