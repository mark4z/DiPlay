import test from 'node:test';
import assert from 'node:assert/strict';

test('embedded RTC selector retains WSS until frame presentation and actual video geometry drives resized touch/resolution', async t => {
  class Events {
    constructor() { this.listeners = new Map(); }
    addEventListener(name, fn) { const list = this.listeners.get(name) || []; list.push(fn); this.listeners.set(name, list); }
    dispatch(name, value = {}) { for (const fn of this.listeners.get(name) || []) fn({ target: this, preventDefault() {}, ...value }); }
  }
  class Element extends Events {
    constructor() {
      super(); this.style = {}; this.dataset = {}; this.value = ''; this.hidden = false; this.checked = false;
      this.textContent = ''; this.width = 1280; this.height = 720; this.children = []; this.captures = new Set(); this.classes = new Set();
      this.bounds = { left: 0, top: 0, width: 1000, height: 1000, right: 1000, bottom: 1000 };
      this.classList = { remove: name => this.classes.delete(name), toggle: (name, value) => value ? this.classes.add(name) : this.classes.delete(name) };
    }
    focus() {} setAttribute() {} removeAttribute() {}
    contains(e) { return e === this || this.children.includes(e); }
    append(...c) { this.children.push(...c); } replaceChildren(...c) { this.children = c; }
    getBoundingClientRect() { return this.bounds; }
    getContext() { return { clearRect() {}, drawImage() {}, fillRect() {} }; }
    setPointerCapture(id) { this.captures.add(id); }
    hasPointerCapture(id) { return this.captures.has(id); }
    releasePointerCapture(id) { this.captures.delete(id); }
  }
  const elements = new Map(), byId = id => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); };
  let next = 0, rtcFrame;
  const raf = new Map(), timers = new Map(), sockets = [], decoders = [], peers = [];
  const video = byId('rtc-video'); video.play = () => Promise.resolve(); video.pause = () => {};
  video.requestVideoFrameCallback = fn => { rtcFrame = fn; return 1; }; video.cancelVideoFrameCallback = () => {};
  class Socket {
    constructor() { this.readyState = 0; this.bufferedAmount = 0; this.sent = []; sockets.push(this); }
    send(text) { this.sent.push(JSON.parse(text)); } close() { this.readyState = 3; }
    receive(message) { this.onmessage({ data: JSON.stringify(message) }); }
  }
  class Decoder {
    static async isConfigSupported() { return { supported: true }; }
    constructor(c) { Object.assign(this, c); this.state = 'unconfigured'; decoders.push(this); }
    configure() { this.state = 'configured'; } close() { this.state = 'closed'; }
    emit() { this.output({ displayWidth: 1920, displayHeight: 1080, close() {} }); }
  }
  const SDP = 'v=0\r\nm=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtcp-mux\r\na=sendonly\r\na=rtpmap:96 H264/90000\r\n';
  class Peer {
    constructor() { peers.push(this); }
    async setRemoteDescription() {} async createAnswer() { return { type: 'answer', sdp: SDP.replace('sendonly', 'recvonly') }; }
    async setLocalDescription() {} async getStats() { return new Map(); } close() {}
  }
  const window = Object.assign(new Events(), { VideoDecoder: Decoder, EncodedVideoChunk: class {}, PointerEvent: class {},
    RTCPeerConnection: Peer, RTCRtpReceiver: { getCapabilities: () => ({ codecs: [{ mimeType: 'video/H264' }] }) }, MediaStream: class {},
    getComputedStyle: () => ({ width: '1000px', height: '1000px', writingMode: 'horizontal-tb', boxSizing: 'content-box' }),
    ResizeObserver: class { observe() {} } }); window.top = window.self = window;
  const document = Object.assign(new Events(), { getElementById: byId, visibilityState: 'visible', createElement: () => new Element() });
  Object.assign(globalThis, { window, document, isSecureContext: true, location: { protocol: 'https:', origin: 'https://tesla.mark4z.asia:9999' },
    WebSocket: Socket, VideoDecoder: Decoder, EncodedVideoChunk: window.EncodedVideoChunk, ResizeObserver: window.ResizeObserver, devicePixelRatio: 1,
    requestAnimationFrame: fn => { raf.set(++next, fn); return next; }, cancelAnimationFrame: id => raf.delete(id) });
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { userAgent: 'Tesla Chromium/130.0' } });
  t.mock.method(globalThis, 'setTimeout', (fn, ms) => { timers.set(++next, { fn, ms }); return next; });
  t.mock.method(globalThis, 'clearTimeout', id => timers.delete(id));
  const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve(); };
  await import('../viewer.mjs?rtc-geometry-test');
  const socket = sockets[0]; socket.readyState = 1; socket.onopen();
  socket.receive({ type: 'approvalPending', version: 2 }); socket.receive({ type: 'authenticated', version: 2 });
  socket.receive({ type: 'config', streamId: 1, codec: 'avc1.640034', width: 2560, height: 1440, videoTransports: ['wss', 'webrtc'] }); await flush();
  decoders[0].emit(); for (const fn of raf.values()) fn(); raf.clear();
  socket.receive({ ...socket.sent.find(m => m.type === 'setTouchOwnership'), type: 'touchOwnership' });
  assert.equal(peers.length, 0);
  const select = byId('video-transport'); select.value = 'webrtc'; select.dispatch('change');
  assert.match(byId('video-transport-status').textContent, /WSS video stays visible/);
  assert.equal(byId('video').style.opacity, '1');
  const start = socket.sent.find(m => m.type === 'rtcStart');
  socket.receive({ ...start, type: 'rtcOffer', codec: 'h264', sdp: SDP }); await flush();
  peers[0].ontrack({ track: { kind: 'video' } });
  assert.equal(byId('video').style.opacity, '1', 'ontrack alone cannot switch renderers');
  video.videoWidth = 3200; video.videoHeight = 1600; video.readyState = 4; rtcFrame(0, { presentedFrames: 1 });
  assert.equal(byId('video').style.opacity, '0'); assert.equal(byId('resolution-actual').textContent, '3200 × 1600 px');
  assert.equal(byId('placeholder').hidden, true);
  const canvas = byId('video');
  const pointer = (id, x, y) => ({ pointerId: id, clientX: x, clientY: y, pointerType: 'touch', buttons: 1 });
  const touches = () => socket.sent.filter(m => m.type === 'touch');
  const count = touches().length;
  canvas.dispatch('pointerdown', pointer(1, 500, 100)); assert.equal(touches().length, count, 'black bar stays untouchable');
  canvas.dispatch('pointerdown', pointer(1, 500, 500));
  assert.equal(touches().at(-1).contacts[0].x, .5); assert.equal(touches().at(-1).contacts[0].y, .5);
  canvas.dispatch('pointerup', pointer(1, 500, 500));
  // RTC object-fit responds immediately to CSS resize, independent of canvas backing pixels.
  canvas.bounds = { left: 0, top: 0, width: 1200, height: 600, right: 1200, bottom: 600 };
  canvas.dispatch('pointerdown', pointer(2, 300, 150));
  assert.equal(touches().at(-1).contacts[0].x, .25); assert.equal(touches().at(-1).contacts[0].y, .25);
  rtcFrame(0, { presentedFrames: 2 });
  video.videoWidth = 1600; video.videoHeight = 1600; rtcFrame(0, { presentedFrames: 3 });
  assert.deepEqual(touches().at(-1).contacts, [], 'actual frame resize releases held contacts');
  assert.equal(byId('resolution-actual').textContent, '1600 × 1600 px');
  assert.equal(socket.sent.some(m => m.type === 'setBrowserResolution'), false, 'switching transport never changes output target');
  select.value = 'wss'; select.dispatch('change');
  assert.equal(byId('video').style.opacity, '1'); assert.equal(byId('placeholder').hidden, false);
  const before = touches().length; canvas.dispatch('pointerdown', pointer(3, 600, 300)); assert.equal(touches().length, before);
  document.visibilityState = 'hidden'; document.dispatch('visibilitychange'); assert.equal(sockets.length, 1);
});
