import test from 'node:test';
import assert from 'node:assert/strict';
import { RtcVideo, sourceCodec, receiverCodecs, validVideoSdp, compatibleAnswer } from '../rtc-video.mjs';

const sdp = (codec = 'h264', direction = 'sendonly', fmtp = '') => `v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtcp-mux\r\na=${direction}\r\na=rtpmap:96 ${codec.toUpperCase()}/90000\r\n${fmtp ? `a=fmtp:96 ${fmtp}\r\n` : ''}`;
function harness({ codecs = ['h264', 'h265'], rvfc = true } = {}) {
  const sent = [], statuses = [], fallbacks = [], frames = [], peers = [], timers = new Map(), callbacks = new Map();
  let id = 0;
  const video = { videoWidth: 0, videoHeight: 0, readyState: 0, quality: 0, srcObject: null,
    play() { return Promise.resolve(); }, pause() {},
    getVideoPlaybackQuality() { return { totalVideoFrames: this.quality }; },
    requestVideoFrameCallback: rvfc ? fn => { callbacks.set(++id, fn); return id; } : undefined,
    cancelVideoFrameCallback: key => callbacks.delete(key),
  };
  class Peer {
    constructor(config) { this.config = config; this.closed = false; this.candidates = []; this.connectionState = 'new'; peers.push(this); }
    async setRemoteDescription(value) { this.remoteDescription = value; }
    async createAnswer() { return { type: 'answer', sdp: this.answer || this.remoteDescription.sdp.replace('sendonly', 'recvonly') }; }
    async setLocalDescription(value) { this.localDescription = value; }
    async addIceCandidate(value) { this.candidates.push(value); }
    async getStats() { return new Map([['video', { type: 'inbound-rtp', kind: 'video', framesDecoded: 2, packetsLost: -1, packetsReceived: 3, bytesReceived: 100 }]]); }
    close() { this.closed = true; }
    track(kind = 'video') { this.ontrack?.({ track: { kind }, streams: [] }); }
  }
  const receiver = new RtcVideo({ video, RTCPeerConnection: Peer,
    RTCRtpReceiver: codecs === null ? {} : { getCapabilities: () => ({ codecs: codecs.map(codec => ({ mimeType: `video/${codec}` })) }) },
    MediaStream: class { constructor(tracks) { this.tracks = tracks; } },
    send: message => { sent.push(message); return true; }, onPresent: size => frames.push(size),
    onStatus: (state, reason) => statuses.push({ state, reason }), onFallback: value => fallbacks.push(value), now: () => 100,
    setTimer: (fn, ms) => { timers.set(++id, { fn, ms }); return id; }, clearTimer: key => timers.delete(key),
  });
  const message = fields => ({ streamId: receiver.attempt.streamId, negotiationId: receiver.attempt.negotiationId, ...fields });
  return { receiver, video, sent, statuses, fallbacks, frames, peers, timers, callbacks, Peer, message,
    offer: (codec = 'h264') => receiver.receive(message({ type: 'rtcOffer', codec, sdp: sdp(codec) })),
    present(width = 1920, height = 1080) {
      video.videoWidth = width; video.videoHeight = height; video.readyState = 2; video.quality++;
      const pending = [...callbacks.values()]; callbacks.clear(); pending.forEach(fn => fn(0, { presentedFrames: video.quality }));
    },
    expire(ms) { for (const [key, timer] of [...timers]) if (timer.ms === ms) { timers.delete(key); timer.fn(); } },
  };
}

test('receiver capability follows actual codec independently of WebCodecs and declines unsupported HEVC without H264 fallback', () => {
  assert.equal(sourceCodec({ codec: 'hvc1.2.4.L153.B0' }), 'h265');
  assert.equal(sourceCodec({ codec: 'avc1.640034' }), 'h264');
  assert.equal(sourceCodec({ codec: 'vp09.00' }), null);
  assert.equal(receiverCodecs({}), null);
  const h = harness({ codecs: ['h264'] });
  assert.equal(h.receiver.start(1, 'h265'), false);
  assert.deepEqual(h.statuses.at(-1), { state: 'wss', reason: 'unsupported-codec' });
  assert.equal(h.sent.length, 0);
  assert.equal(h.peers.length, 0);
});

test('SDP rejects audio, data, inactive/rejected video, different codec and incompatible actual profile/level', () => {
  assert.equal(validVideoSdp(sdp(), 'h264'), true);
  assert.equal(validVideoSdp(sdp() + 'm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n', 'h264'), false);
  assert.equal(validVideoSdp(sdp() + 'm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n', 'h264'), false);
  assert.equal(validVideoSdp(sdp().replace('video 9', 'video 0'), 'h264'), false);
  assert.equal(validVideoSdp(sdp('h265'), 'h264'), false);
  assert.equal(compatibleAnswer(sdp('h264', 'sendonly', 'profile-level-id=640034;packetization-mode=1'), sdp('h264', 'recvonly', 'profile-level-id=42e01f;packetization-mode=1'), 'h264'), false);
  assert.equal(compatibleAnswer(sdp('h265', 'sendonly', 'profile-id=2;level-id=153'), sdp('h265', 'recvonly', 'profile-id=2;level-id=153'), 'h265'), true);
});

test('offer yields one video-only peer, ordered answer/trickle and no handover before a presented frame', async () => {
  const h = harness(); h.receiver.start(11, 'h265');
  const candidate = { type: 'rtcCandidate', candidate: 'candidate:1 1 udp 1 192.168.1.2 4000 typ host', sdpMid: '0', sdpMLineIndex: 0 };
  await h.receiver.receive(h.message(candidate));
  await h.offer('h265');
  assert.deepEqual(h.peers[0].config.iceServers, []);
  assert.equal(h.peers[0].candidates.length, 1);
  assert.equal(h.sent[1].type, 'rtcAnswer');
  assert.ok(h.sent[1].sdp.includes('H265'));
  await h.offer('h265'); assert.equal(h.peers.length, 1);
  h.peers[0].track();
  assert.equal(h.video.srcObject.tracks[0].kind, 'video');
  assert.equal(h.video.muted, true); assert.equal(h.video.playsInline, true);
  assert.equal(h.sent.some(m => m.type === 'rtcReady'), false);
  h.present(2560, 1440);
  assert.deepEqual(h.frames[0], { width: 2560, height: 1440, first: true });
  assert.equal(h.sent.filter(m => m.type === 'rtcReady').length, 1);
  await h.receiver.receive(h.message({ type: 'rtcState', state: 'active' }));
  h.present(3200, 1800);
  assert.deepEqual(h.frames.at(-1), { width: 3200, height: 1800, first: false });
  assert.equal(h.sent.filter(m => m.type === 'rtcReady').length, 1);
  assert.deepEqual(h.statuses.at(-1), { state: 'webrtc', reason: null });
});

test('absent capability API uses SDP final gate, rejecting a wrong codec answer without disrupting control', async () => {
  const h = harness({ codecs: null });
  h.Peer.prototype.createAnswer = async () => ({ type: 'answer', sdp: sdp('h264', 'recvonly') });
  h.receiver.start(1, 'h265'); await h.offer('h265');
  assert.equal(h.receiver.attempt, null);
  assert.equal(h.sent.at(-1).type, 'rtcStop');
  assert.equal(h.sent.at(-1).reason, 'unsupported-codec');
  assert.equal(h.fallbacks[0].presented, false);
});

test('stale description continuations, ICE, frame callbacks and timers cannot revive a closed/replacement peer', async () => {
  const h = harness(); let finish;
  h.Peer.prototype.setRemoteDescription = () => new Promise(resolve => { finish = resolve; });
  h.receiver.start(1, 'h264');
  const oldId = h.receiver.attempt.negotiationId;
  const pending = h.offer();
  const oldPeer = h.peers[0], ice = oldPeer.onicecandidate;
  const staleTimers = [...h.timers.values()];
  h.receiver.close();
  h.receiver.start(2, 'h264');
  finish(); await pending;
  ice({ candidate: { candidate: 'late', sdpMid: '0', sdpMLineIndex: 0 } });
  for (const timer of staleTimers) timer.fn();
  await h.receiver.receive({ type: 'rtcState', state: 'fallback', streamId: 1, negotiationId: oldId });
  assert.equal(h.sent.filter(m => m.type === 'rtcAnswer').length, 0);
  assert.equal(h.receiver.attempt.streamId, 2);
  assert.equal(oldPeer.closed, true);
  assert.equal(h.fallbacks.length, 0);
});

test('candidate and signaling limits fail only the RTC attempt and clear bounded timers', async () => {
  const h = harness(); h.receiver.start(1, 'h264');
  for (let i = 0; i < 17; i++) {
    const a = h.receiver.attempt;
    if (a) await h.receiver.receive(h.message({ type: 'rtcCandidate', candidate: 'candidate', sdpMid: '0', sdpMLineIndex: 0 }));
  }
  assert.equal(h.receiver.attempt, null); assert.equal(h.timers.size, 0);
  h.receiver.start(2, 'h264');
  await h.receiver.receive(h.message({ type: 'rtcOffer', codec: 'h264', sdp: sdp() + 'x'.repeat(6144) }));
  assert.equal(h.receiver.attempt, null);
});

test('first frame and ack have bounded deadlines; static displayed screens do not trigger frame-stall fallback', async () => {
  const h = harness(); h.receiver.start(1, 'h264'); h.expire(10000);
  assert.equal(h.fallbacks[0].reason, 'first-frame-timeout');
  h.receiver.start(2, 'h264'); await h.offer(); h.peers.at(-1).track(); h.present();
  h.expire(10000); assert.equal(h.fallbacks.at(-1).presented, true);
  h.receiver.start(3, 'h264'); await h.offer(); h.peers.at(-1).track(); h.present();
  await h.receiver.receive(h.message({ type: 'rtcState', state: 'active' }));
  h.expire(10000); assert.equal(h.receiver.attempt.streamId, 3);
});

test('disconnected grace is bounded and failed state falls back immediately; close releases media and timers', async () => {
  const h = harness(); h.receiver.start(1, 'h264'); await h.offer();
  const peer = h.peers[0]; peer.connectionState = 'disconnected'; peer.onconnectionstatechange();
  const timer = h.receiver.attempt.recovery; peer.onconnectionstatechange();
  assert.equal(h.receiver.attempt.recovery, timer);
  peer.connectionState = 'failed'; peer.onconnectionstatechange();
  assert.equal(h.fallbacks[0].reason, 'connection-failed');
  assert.equal(h.video.srcObject, null); assert.equal(h.timers.size, 0); assert.equal(peer.closed, true);
});

test('without RVFC, changing decoded playback-quality count is required, metadata/readyState alone are insufficient', async () => {
  const h = harness({ rvfc: false }); h.video.quality = 50;
  h.receiver.start(1, 'h264'); await h.offer(); h.peers[0].track();
  h.video.videoWidth = 1280; h.video.videoHeight = 720; h.video.readyState = 4;
  h.expire(100); assert.equal(h.frames.length, 0);
  h.video.quality++; h.expire(100); assert.equal(h.frames.length, 1);
  h.receiver.stop(); assert.equal(h.fallbacks[0].reason, 'user-selected-wss');
});

test('rejected play and unexpected audio tracks fail safely, stats omit missing metrics and accept signed loss', async () => {
  const h = harness(); h.receiver.start(1, 'h264'); await h.offer();
  await Promise.resolve();
  const report = h.sent.find(m => m.type === 'rtcStats');
  assert.equal(report.packetsLost, -1); assert.equal('framesPresented' in report, false);
  h.peers[0].track('audio'); assert.equal(h.receiver.attempt, null);
  h.receiver.start(2, 'h264'); await h.offer();
  h.video.play = () => Promise.reject(Error('blocked')); h.peers.at(-1).track();
  await Promise.resolve(); assert.equal(h.fallbacks.at(-1).reason, 'render-failed');
});

test('trickled candidates during a queued drain remain ordered and stale frame callbacks cannot switch renderers', async () => {
  const h = harness(); let release;
  h.Peer.prototype.addIceCandidate = function(candidate) {
    this.candidates.push(candidate);
    if (candidate.candidate === 'first') return new Promise(resolve => { release = resolve; });
    return Promise.resolve();
  };
  h.receiver.start(1, 'h264');
  const candidate = value => h.message({ type: 'rtcCandidate', candidate: value, sdpMid: '0', sdpMLineIndex: 0 });
  await h.receiver.receive(candidate('first')); await h.receiver.receive(candidate('second'));
  const offer = h.offer(); for (let i = 0; i < 3; i++) await Promise.resolve();
  const third = h.receiver.receive(candidate('third')); release(); await offer; await third;
  assert.deepEqual(h.peers[0].candidates.map(c => c.candidate), ['first', 'second', 'third']);
  h.peers[0].track(); const callback = [...h.callbacks.values()][0];
  h.receiver.close(); h.video.videoWidth = 1920; h.video.videoHeight = 1080; h.video.readyState = 4;
  callback(0, { presentedFrames: 1 }); assert.equal(h.frames.length, 0); assert.equal(h.callbacks.size, 0);
});

test('a receiver may accept a higher level of the same source profile, but not a lower level or packetization mode', () => {
  const offer = sdp('h264', 'sendonly', 'profile-level-id=640033;packetization-mode=1');
  assert.equal(compatibleAnswer(offer, sdp('h264', 'recvonly', 'profile-level-id=640034;packetization-mode=1'), 'h264'), true);
  assert.equal(compatibleAnswer(offer, sdp('h264', 'recvonly', 'profile-level-id=640032;packetization-mode=1'), 'h264'), false);
  assert.equal(compatibleAnswer(offer, sdp('h264', 'recvonly', 'profile-level-id=640034;packetization-mode=0'), 'h264'), false);
});

test('local ICE gathered while applying the answer is sent after the answer, never as an SDP expansion', async () => {
  const h = harness();
  h.Peer.prototype.setLocalDescription = async function(value) {
    this.localDescription = { ...value, sdp: value.sdp + 'x'.repeat(10000) };
    this.onicecandidate({ candidate: { candidate: 'candidate:1 1 udp 1 192.168.1.2 4000 typ host', sdpMid: '0', sdpMLineIndex: 0 } });
  };
  h.receiver.start(1, 'h264'); await h.offer();
  assert.deepEqual(h.sent.filter(m => ['rtcAnswer', 'rtcCandidate'].includes(m.type)).map(m => m.type), ['rtcAnswer', 'rtcCandidate']);
  assert.ok(h.sent.find(m => m.type === 'rtcAnswer').sdp.length < 6144);
});
