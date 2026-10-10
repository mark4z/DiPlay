import test from 'node:test';
import assert from 'node:assert/strict';
import { BrowserSession } from '../session.mjs';
const CONFIG = { type: 'config', streamId: 1, codec: 'avc1.640034', width: 2560, height: 1440, videoTransports: ['wss', 'webrtc'] };
const SDP = 'v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtcp-mux\r\na=sendonly\r\na=rtpmap:96 H264/90000\r\n';
function harness() {
  const sockets = [], decoders = [], peers = [], frames = [], statuses = [], renderers = [], timers = new Map(); let id = 0, frameCallback;
  class Socket {
    constructor() { this.readyState = 1; this.bufferedAmount = 0; this.sent = []; sockets.push(this); }
    send(text) { this.sent.push(JSON.parse(text)); }
    close() { this.readyState = 3; }
    receive(message) { this.onmessage({ data: JSON.stringify(message) }); }
  }
  class Decoder {
    static async isConfigSupported() { return { supported: true }; }
    constructor(callbacks) { Object.assign(this, callbacks); this.state = 'unconfigured'; this.chunks = []; decoders.push(this); }
    configure() { this.state = 'configured'; }
    close() { this.state = 'closed'; }
    decode(chunk) { this.chunks.push(chunk); }
    emit() { const frame = { closed: false, close() { this.closed = true; } }; this.output(frame); return frame; }
  }
  class Peer {
    constructor() { peers.push(this); }
    async setRemoteDescription() {}
    async createAnswer() { return { type: 'answer', sdp: SDP.replace('sendonly', 'recvonly') }; }
    async setLocalDescription() {}
    async getStats() { return new Map(); }
    close() { this.closed = true; }
  }
  const video = { videoWidth: 2560, videoHeight: 1440, readyState: 4,
    play: () => Promise.resolve(), pause() {}, requestVideoFrameCallback: fn => { frameCallback = fn; return 1; }, cancelVideoFrameCallback() {} };
  const session = new BrowserSession({ WebSocket: Socket, VideoDecoder: Decoder, EncodedVideoChunk: class { constructor(chunk) { Object.assign(this, chunk); } },
    autoTouch: true, rtc: { video, RTCPeerConnection: Peer, RTCRtpReceiver: { getCapabilities: () => ({ codecs: [{ mimeType: 'video/H264' }] }) }, MediaStream: class {} },
    onState() {}, onFrame: frame => frames.push(frame), onRenderer: renderer => renderers.push(renderer), onVideoTransport: status => statuses.push(status),
    setTimer: (callback, delay) => { timers.set(++id, { callback, delay }); return id; }, clearTimer: id => timers.delete(id), now: () => 0 });
  return { session, sockets, decoders, peers, frames, statuses, renderers, timers, video,
    async ready(config = CONFIG) {
      session.connect(undefined, undefined, 'https://tesla.mark4z.asia:9999');
      const socket = sockets[0]; socket.onopen();
      socket.receive({ type: 'approvalPending', version: 2 }); socket.receive({ type: 'authenticated', version: 2 });
      socket.receive(config); await Promise.resolve(); decoders[0].emit();
      socket.receive({ ...socket.sent.at(-1), type: 'touchOwnership' }); return socket;
    },
    async offered() {
      session.selectVideoTransport('webrtc');
      const a = session.rtc.attempt;
      await session.rtc.receive({ type: 'rtcOffer', streamId: a.streamId, negotiationId: a.negotiationId, codec: 'h264', sdp: SDP });
      return a;
    },
    present() { peers.at(-1).ontrack({ track: { kind: 'video' } }); frameCallback(0, { presentedFrames: 1 }); },
  };
}
function packet(key) {
  const b = new ArrayBuffer(15), v = new DataView(b); v.setUint8(0, key ? 1 : 2); v.setBigUint64(1, 1n);
  new Uint8Array(b, 9).set([0, 0, 0, 1, key ? 0x65 : 0x41, 0x80]); return b;
}

test('default WSS makes no peer; failed negotiation preserves live renderer, approval and touch ownership', async () => {
  const h = harness(), socket = await h.ready();
  assert.equal(h.peers.length, 0); assert.equal(h.session.touchOwned, true);
  const a = await h.offered();
  const before = socket.sent.filter(m => m.type === 'setTouchOwnership').length;
  await h.session.rtc.receive({ type: 'rtcState', state: 'fallback', reason: 'ice-failed', streamId: a.streamId, negotiationId: a.negotiationId });
  assert.equal(h.session.authenticated, true); assert.equal(h.session.closed, false);
  assert.equal(h.session.streaming, true); assert.equal(h.session.touchOwned, true);
  socket.receive(CONFIG); await Promise.resolve();
  assert.equal(socket.sent.filter(m => m.type === 'rtcStart').length, 1, 'fallback config does not loop negotiation');
  assert.equal(socket.sent.filter(m => m.type === 'setTouchOwnership').length, before);
  assert.equal(h.decoders.length, 1);
  h.session.selectVideoTransport('webrtc'); assert.equal(socket.sent.filter(m => m.type === 'rtcStart').length, 2);
});

test('presented RTC takes over only video; fallback retains identity and requires a new WSS keyframe/output before touch', async () => {
  const h = harness(), socket = await h.ready(), a = await h.offered();
  const decoder = h.decoders[0]; h.present();
  assert.equal(h.session.rtcPresented, true); assert.equal(h.session.touchOwned, true);
  assert.equal(h.renderers.at(-1), 'webrtc');
  assert.equal(decoder.emit().closed, true, 'queued WSS output cannot replace RTC');
  await h.session.rtc.receive({ type: 'rtcState', state: 'active', streamId: a.streamId, negotiationId: a.negotiationId });
  assert.equal(decoder.state, 'closed');
  h.session.selectVideoTransport('wss');
  assert.equal(h.session.authenticated, true); assert.equal(h.session.streamId, 1);
  assert.equal(h.session.streaming, false); assert.equal(h.session.touchOwned, false); assert.equal(h.session.touchRequested, true);
  assert.equal(h.session.sendContacts([{ x: .5, y: .5 }]), false);
  const fresh = h.decoders.at(-1); h.session.receiveVideo(packet(false)); assert.equal(fresh.chunks.length, 0);
  h.session.receiveVideo(packet(true)); assert.equal(fresh.chunks.length, 1);
  assert.equal(h.session.streaming, false); fresh.emit(); assert.equal(h.session.streaming, true);
  assert.equal(h.session.touchPending, true);
  assert.equal(socket.sent.some(m => m.type === 'setBrowserResolution'), false, 'transport selection cannot restart CarPlay');
});

test('unsupported HEVC and old APK preserve WSS and codec/size, with an explicit reason', async () => {
  const h = harness(); await h.ready({ ...CONFIG, codec: 'hvc1.2.4.L153.B0' });
  h.session.selectVideoTransport('webrtc');
  assert.equal(h.statuses.at(-1).reason, 'unsupported-codec'); assert.equal(h.peers.length, 0);
  assert.equal(h.session.config.codec, 'hvc1.2.4.L153.B0'); assert.equal(h.session.config.codedWidth, 2560);
  assert.equal(h.session.touchOwned, true);
  const old = harness(); await old.ready({ ...CONFIG, videoTransports: undefined }); old.session.selectVideoTransport('webrtc');
  assert.equal(old.statuses.at(-1).reason, 'native-unavailable'); assert.equal(old.session.closed, false);
});

test('close/config replacement invalidates peer and callbacks without resetting chosen transport or allowing stale frames', async () => {
  const h = harness(), socket = await h.ready(); await h.offered();
  const oldPeer = h.peers[0], callback = oldPeer.ontrack;
  socket.receive({ ...CONFIG, streamId: 2 }); await Promise.resolve();
  assert.equal(oldPeer.closed, true); callback({ track: { kind: 'video' } });
  assert.equal(h.session.rtcPresented, false); assert.equal(h.session.streamId, 2);
  assert.equal(socket.sent.filter(m => m.type === 'rtcStart').length, 2);
  h.session.close(); assert.equal(h.session.rtc.attempt, null); assert.equal(h.timers.size, 0);
});

test('RTC signaling congestion never closes the approved WSS control session', async () => {
  const h = harness(), socket = await h.ready(); socket.bufferedAmount = 20000;
  h.session.selectVideoTransport('webrtc');
  assert.equal(h.session.rtc.attempt, null); assert.equal(h.session.closed, false);
  assert.equal(h.session.authenticated, true); assert.equal(h.session.touchOwned, true);
});
