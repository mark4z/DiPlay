// Video-only RTC receiver. Authenticated WSS owns signaling and all controls.
const bytes = value => new TextEncoder().encode(value).length;
const token = value => typeof value === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(value);
export const sourceCodec = config => /^(hvc1|hev1)/i.test(config?.codec || '') ? 'h265' : /^avc[13]/i.test(config?.codec || '') ? 'h264' : null;
export function receiverCodecs(Receiver) {
  if (typeof Receiver?.getCapabilities !== 'function') return null; // SDP is authoritative when API is absent.
  try {
    const capabilities = Receiver.getCapabilities('video');
    return ['h264', 'h265'].filter(codec => capabilities?.codecs?.some(item =>
      String(item.mimeType).toLowerCase() === `video/${codec}`));
  } catch { return []; }
}
export function validVideoSdp(sdp, codec, answer = false) {
  if (typeof sdp !== 'string' || bytes(sdp) > 6144 || !['h264', 'h265'].includes(codec)) return false;
  const lines = sdp.split(/\r?\n/);
  const media = lines.filter(line => line.startsWith('m='));
  if (media.length !== 1 || !/^m=video (?!0(?: |$))\d+ UDP\/TLS\/RTP\/SAVPF /.test(media[0])) return false;
  const payloads = media[0].trim().split(/\s+/).slice(3);
  if (!lines.includes('a=rtcp-mux') || !lines.includes(answer ? 'a=recvonly' : 'a=sendonly')) return false;
  const maps = lines.map(line => /^a=rtpmap:(\d+) ([^/]+)\/90000$/i.exec(line)).filter(Boolean);
  return maps.some(match => payloads.includes(match[1]) && match[2].toLowerCase() === codec) &&
    !maps.some(match => payloads.includes(match[1]) && ![codec, 'rtx', 'red', 'ulpfec', 'flexfec-03'].includes(match[2].toLowerCase()));
}
export function compatibleAnswer(offer, answer, codec) {
  if (!validVideoSdp(answer, codec, true)) return false;
  const payloads = sdp => [...sdp.matchAll(/^a=rtpmap:(\d+) ([^/]+)\/90000\r?$/gmi)]
    .filter(match => match[2].toLowerCase() === codec).map(match => match[1]);
  const parameters = (sdp, pt) => {
    const line = sdp.split(/\r?\n/).find(line => line.startsWith(`a=fmtp:${pt} `));
    return Object.fromEntries((line?.slice(line.indexOf(' ') + 1) || '').split(';').map(item => item.trim().toLowerCase().split('=')));
  };
  const offered = payloads(offer);
  const accepted = payloads(answer);
  return accepted.length > 0 && accepted.every(pt => {
    if (!offered.includes(pt)) return false;
    const source = parameters(offer, pt), receiver = parameters(answer, pt);
    if (codec === 'h264') {
      const profile = source['profile-level-id'], acceptedProfile = receiver['profile-level-id'];
      if (source['packetization-mode'] !== undefined && source['packetization-mode'] !== receiver['packetization-mode']) return false;
      return profile === undefined || (typeof acceptedProfile === 'string' && /^[0-9a-f]{6}$/.test(acceptedProfile) &&
        acceptedProfile.slice(0, 4) === profile.slice(0, 4) && parseInt(acceptedProfile.slice(4), 16) >= parseInt(profile.slice(4), 16));
    }
    return ['profile-space', 'profile-id', 'tier-flag'].every(key => source[key] === undefined ||
      source[key] === (receiver[key] ?? (key === 'profile-id' ? '1' : '0'))) &&
      (source['level-id'] === undefined || (receiver['level-id'] !== undefined && Number(receiver['level-id']) >= Number(source['level-id'])));
  });
}
function candidateFields(value) {
  if (typeof value.candidate !== 'string' || bytes(value.candidate) > 1024 ||
      typeof value.sdpMid !== 'string' || bytes(value.sdpMid) > 32 || /[\r\n]/.test(value.candidate + value.sdpMid) || value.sdpMLineIndex !== 0) return null;
  return { candidate: value.candidate, sdpMid: value.sdpMid, sdpMLineIndex: 0 };
}

export class RtcVideo {
  constructor({ video, RTCPeerConnection = globalThis.RTCPeerConnection, RTCRtpReceiver = globalThis.RTCRtpReceiver,
    MediaStream = globalThis.MediaStream, send, onPresent = () => {}, onFallback = () => {}, onStatus = () => {},
    now = () => performance.now(), setTimer = (fn, ms) => globalThis.setTimeout(fn, ms), clearTimer = id => globalThis.clearTimeout(id) }) {
    Object.assign(this, { video, RTCPeerConnection, RTCRtpReceiver, MediaStream, send, onPresent, onFallback, onStatus, now, setTimer, clearTimer });
    this.attempt = null;
    this.sequence = 0;
  }
  start(streamId, codec) {
    this.close();
    if (!this.video || typeof this.RTCPeerConnection !== 'function') { this.onStatus('wss', 'browser-unavailable'); return false; }
    const codecs = receiverCodecs(this.RTCRtpReceiver);
    if (!codec || (codecs && !codecs.includes(codec))) { this.onStatus('wss', 'unsupported-codec'); return false; }
    const a = this.attempt = { streamId, codec, negotiationId: `v${Date.now().toString(36)}_${++this.sequence}`,
      pc: null, remote: false, offered: false, iceChain: Promise.resolve(), received: 0, sent: 0, candidates: [], outbound: [], answerSent: false,
      presented: false, active: false, framesPresented: 0, reportId: 0, timers: new Set(), frameCallback: null };
    this.arm(a, 'deadline', 10000, () => this.fail(a, 'first-frame-timeout'));
    this.onStatus('negotiating', null);
    if (!this.signal(a, { type: 'rtcStart', codecs: codecs || [codec] })) this.fail(a, 'negotiation-failed');
    return this.current(a);
  }
  current(a) { return this.attempt === a; }
  signal(a, message) {
    if (!this.current(a)) return false;
    const payload = { ...message, streamId: a.streamId, negotiationId: a.negotiationId };
    return bytes(JSON.stringify(payload)) <= 8192 && this.send(payload);
  }
  arm(a, name, delay, callback) {
    this.cancel(a, name);
    a[name] = this.setTimer(() => { a.timers.delete(a[name]); a[name] = null; if (this.current(a)) callback(); }, delay);
    a.timers.add(a[name]);
  }
  cancel(a, name) { if (a[name] != null) { this.clearTimer(a[name]); a.timers.delete(a[name]); a[name] = null; } }
  async receive(message) {
    const a = this.attempt;
    if (!a || message.streamId !== a.streamId || !token(message.negotiationId) || message.negotiationId !== a.negotiationId) return;
    if (bytes(JSON.stringify(message)) > 8192) { this.fail(a, 'negotiation-failed'); return; }
    if (message.type === 'rtcState') {
      if (message.state === 'fallback') { this.fail(a, 'negotiation-failed', false, token(message.reason) ? message.reason : 'negotiation-failed'); return; }
      if (message.state === 'active' && a.presented) { a.active = true; this.cancel(a, 'ackDeadline'); this.onStatus('webrtc', null); }
      return;
    }
    try {
      if (message.type === 'rtcOffer') {
        if (a.offered) return;
        a.offered = true;
        if (message.codec !== a.codec || !validVideoSdp(message.sdp, a.codec)) throw Error('invalid offer');
        const pc = a.pc = new this.RTCPeerConnection({ iceServers: [], bundlePolicy: 'max-bundle' });
        pc.onicecandidate = event => {
          if (!this.current(a) || !event.candidate) return;
          const candidate = candidateFields({ ...event.candidate.toJSON?.(), candidate: event.candidate.candidate,
            sdpMid: event.candidate.sdpMid ?? '0', sdpMLineIndex: event.candidate.sdpMLineIndex ?? 0 });
          if (!candidate || ++a.sent > 16) { this.fail(a, 'negotiation-failed'); return; }
          if (!a.answerSent) a.outbound.push(candidate);
          else if (!this.signal(a, { type: 'rtcCandidate', ...candidate })) this.fail(a, 'negotiation-failed');
        };
        pc.onconnectionstatechange = pc.oniceconnectionstatechange = () => {
          if (!this.current(a)) return;
          const states = [pc.connectionState, pc.iceConnectionState];
          if (states.includes('failed') || states.includes('closed')) this.fail(a, 'connection-failed');
          else if (states.includes('disconnected')) {
            if (a.recovery == null) this.arm(a, 'recovery', 10000, () => this.fail(a, 'recovery-timeout'));
          } else if (states.includes('connected') || states.includes('completed')) this.cancel(a, 'recovery');
        };
        pc.ontrack = event => {
          if (!this.current(a)) return;
          if (event.track?.kind !== 'video') { this.fail(a, 'negotiation-failed'); return; }
          event.track.onended = () => { if (this.current(a)) this.fail(a, 'connection-failed'); };
          this.video.muted = true;
          this.video.playsInline = true;
          this.video.srcObject = new this.MediaStream([event.track]);
          this.watchFrames(a);
          try { Promise.resolve(this.video.play()).catch(() => this.fail(a, 'render-failed')); }
          catch { this.fail(a, 'render-failed'); }
        };
        await pc.setRemoteDescription({ type: 'offer', sdp: message.sdp });
        if (!this.current(a)) return;
        a.remote = true;
        for (const candidate of a.candidates.splice(0)) this.addCandidate(a, candidate);
        await a.iceChain;
        if (!this.current(a)) return;
        const answer = await pc.createAnswer();
        if (!this.current(a)) return;
        if (!compatibleAnswer(message.sdp, answer.sdp, a.codec)) { this.fail(a, 'unsupported-codec'); return; }
        await pc.setLocalDescription(answer);
        if (!this.current(a)) return;
        // Trickle: use the original answer before gathered candidates inflate localDescription.
        if (!this.signal(a, { type: 'rtcAnswer', sdp: answer.sdp })) { this.fail(a, 'negotiation-failed'); return; }
        a.answerSent = true;
        for (const candidate of a.outbound.splice(0)) if (!this.signal(a, { type: 'rtcCandidate', ...candidate })) { this.fail(a, 'negotiation-failed'); return; }
        this.pollStats(a);
      } else if (message.type === 'rtcCandidate') {
        const candidate = candidateFields(message);
        if (!candidate || ++a.received > 16) throw Error('invalid candidate');
        if (!a.remote) a.candidates.push(candidate);
        else await this.addCandidate(a, candidate);
      }
    } catch { this.fail(a, 'negotiation-failed'); }
  }
  addCandidate(a, candidate) {
    a.iceChain = a.iceChain.then(() => {
      if (this.current(a)) return a.pc.addIceCandidate(candidate);
    }).catch(() => this.fail(a, 'negotiation-failed'));
    return a.iceChain;
  }
  watchFrames(a) {
    if (!this.current(a)) return;
    const frame = metadata => {
      if (!this.current(a)) return;
      const width = this.video.videoWidth, height = this.video.videoHeight;
      if (width > 0 && height > 0 && this.video.readyState >= 2) {
        a.framesPresented = Number.isSafeInteger(metadata?.presentedFrames) ? metadata.presentedFrames : a.framesPresented + 1;
        const first = !a.presented;
        a.presented = true;
        try {
          if (first || width !== a.width || height !== a.height) this.onPresent({ width, height, first });
          a.width = width; a.height = height;
        }
        catch { this.fail(a, 'render-failed'); return; }
        if (!this.current(a)) return;
        if (first) {
          this.cancel(a, 'deadline');
          this.arm(a, 'ackDeadline', 10000, () => this.fail(a, 'recovery-timeout'));
          if (!this.signal(a, { type: 'rtcReady' })) { this.fail(a, 'negotiation-failed'); return; }
        }
      }
    };
    if (typeof this.video.requestVideoFrameCallback === 'function') {
      const next = (_time, metadata) => { frame(metadata); if (this.current(a)) a.frameCallback = this.video.requestVideoFrameCallback(next); };
      a.frameCallback = this.video.requestVideoFrameCallback(next);
    } else {
      // Playback-quality count is evidence of decoded video, unlike loadedmetadata/ontrack.
      const presentedCount = () => {
        const quality = this.video.getVideoPlaybackQuality?.();
        return quality ? quality.totalVideoFrames - (quality.droppedVideoFrames || 0) : undefined;
      };
      let previous = presentedCount() || 0;
      const check = () => {
        const count = presentedCount();
        if (Number.isSafeInteger(count) && count > previous) { previous = count; frame({ presentedFrames: count }); }
        if (this.current(a)) this.arm(a, 'framePoll', 100, check);
      };
      check();
    }
  }
  async pollStats(a) {
    if (!this.current(a)) return;
    try {
      const stats = await a.pc.getStats();
      if (!this.current(a)) return;
      const report = { type: 'rtcStats', reportId: ++a.reportId, timestampMs: this.now() };
      if (a.presented) report.framesPresented = a.framesPresented;
      stats.forEach(stat => {
        if (stat.type !== 'inbound-rtp' || (stat.kind || stat.mediaType) !== 'video') return;
        for (const key of ['framesDecoded', 'packetsReceived', 'packetsLost', 'bytesReceived']) {
          if (Number.isSafeInteger(stat[key]) && (key === 'packetsLost' || stat[key] >= 0)) report[key] = stat[key];
        }
        if (Number.isFinite(stat.jitter) && stat.jitter >= 0) report.jitterMs = stat.jitter * 1000;
      });
      this.signal(a, report);
    } catch { /* Stats are optional diagnostics, not a static-screen stall detector. */ }
    if (this.current(a)) this.arm(a, 'statsTimer', 1000, () => this.pollStats(a));
  }
  fail(a, reason, notify = true, displayReason = reason) {
    if (!this.current(a)) return;
    if (notify) this.signal(a, { type: 'rtcStop', reason });
    const presented = a.presented;
    this.close();
    this.onStatus('wss', displayReason);
    this.onFallback({ presented, reason: displayReason });
  }
  stop() { if (this.attempt) this.fail(this.attempt, 'user-selected-wss'); }
  close() {
    const a = this.attempt;
    this.attempt = null;
    if (!a) return;
    for (const timer of a.timers) this.clearTimer(timer);
    if (a.frameCallback != null) this.video.cancelVideoFrameCallback?.(a.frameCallback);
    if (a.pc) {
      a.pc.ontrack = a.pc.onicecandidate = a.pc.onconnectionstatechange = a.pc.oniceconnectionstatechange = null;
      a.pc.close();
    }
    this.video.pause();
    this.video.srcObject = null;
  }
}
