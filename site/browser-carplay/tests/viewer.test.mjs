import test from 'node:test';
import assert from 'node:assert/strict';

// Minimal DOM/WebCodecs stubs exercise the actual page module's event wiring.
// This supplements (not replaces) a real browser/hardware acceptance test.
for (const embedded of [false, true]) {
test(`${embedded ? 'embedded TLS' : 'external LAN'} viewer preserves connection policy, automatic touch and releases frames/contacts on interrupted flows`, async t => {
  class Events {
    constructor() { this.listeners = new Map(); }
    addEventListener(name, handler) { const handlers = this.listeners.get(name) || []; handlers.push(handler); this.listeners.set(name, handlers); }
    dispatch(name, data = {}) { for (const handler of this.listeners.get(name) || []) handler({ preventDefault() {}, ...data }); }
  }
  const draws = [], captured = new Set();
  class Element extends Events {
    constructor() {
      super(); this.style = {}; this.attributes = {}; this.value = ''; this.disabled = false; this.checked = false; this.hidden = false; this.textContent = '';
      this.dataset = {}; this.width = 1280; this.height = 720;
      this.children = [];
      this.bounds = { left: 0, top: 0, width: 1000, height: 1000, right: 1000, bottom: 1000 };
      this.classes = new Set();
      this.classList = { remove: name => this.classes.delete(name),
        toggle: (name, enabled) => enabled ? this.classes.add(name) : this.classes.delete(name) };
    }
    focus() {}
    contains(element) { return element === this || this.children.includes(element); }
    setAttribute(name, value) { this.attributes[name] = value; }
    removeAttribute(name) { delete this.attributes[name]; }
    getContext() { return { clearRect() {}, fillRect() {}, drawImage: (...args) => draws.push(args) }; }
    replaceChildren(...children) { this.children = children; }
    append(...children) { this.children.push(...children); }
    getBoundingClientRect() { return this.bounds; }
    setPointerCapture(id) { captured.add(id); }
    hasPointerCapture(id) { return captured.has(id); }
    releasePointerCapture(id) { captured.delete(id); this.dispatch('lostpointercapture', { pointerId: id }); }
  }
  const elements = Object.fromEntries(['resolution-target', 'resolution-actual', 'settings-close', 'resolution-settings', 'resolution-follow', 'resolution-status', 'controls-grip', 'controls-reveal', 'controls-safe-area', 'connection', 'ip', 'port', 'parked', 'touch', 'connect', 'disconnect',
    'video', 'viewport', 'placeholder', 'status', 'indicator', 'origin', 'touch-status',
    'connection-timeline', 'connection-attempt', 'connection-transport', 'manual-endpoint', 'local-endpoint',
    'local-endpoint-value', 'connection-instructions', 'transport-warning', 'browser-requirements', 'lan-diagnostics',
    'connection-troubleshooting', 'viewer-shell', 'enter', 'retry', 'stop', 'entry-status', 'embedded-controls',
    'page-header', 'intro', 'parked-control', 'settings-content', 'placeholder-title', 'placeholder-note',
    'parking-explanation', 'local-approval-help', 'touch-control', 'setup', 'diagnostics',
    'display-note', 'requirements', 'legal', 'compact-touch-state', 'display-toolbar', 'viewer-settings'].map(id => [id, new Element()]));
  elements.parked.checked = true;
  elements.touch.checked = true;
  elements.ip.value = 'untrusted.invalid'; // Restored form values cannot override the embedded endpoint.
  elements.port.value = '12345';
  const document = Object.assign(new Events(), { visibilityState: 'visible', getElementById: id => elements[id], createElement: () => new Element() });
  const sockets = [], decoders = [], raf = new Map();
  let rafId = 0, fetches = 0;
  class Socket {
    constructor(url) { this.url = url; this.readyState = 0; this.bufferedAmount = 0; this.sent = []; sockets.push(this); }
    open() { this.readyState = 1; this.onopen?.(); }
    send(text) { this.sent.push(JSON.parse(text)); }
    receive(message) { this.onmessage?.({ data: JSON.stringify(message) }); }
    close() { this.readyState = 3; }
  }
  class Decoder {
    static async isConfigSupported() { return { supported: true }; }
    constructor(callbacks) { Object.assign(this, callbacks); this.state = 'unconfigured'; decoders.push(this); }
    configure() { this.state = 'configured'; }
    close() { this.state = 'closed'; }
    emit() {
      const frame = { displayWidth: 1920, displayHeight: 1080, closes: 0, close() { this.closes++; } };
      this.output(frame); return frame;
    }
  }
  const timers = new Map(); let nextTimer = 0;
  t.mock.method(globalThis, 'setTimeout', (callback, delay) => { timers.set(++nextTimer, { callback, delay }); return nextTimer; });
  t.mock.method(globalThis, 'clearTimeout', id => timers.delete(id));
  const window = Object.assign(new Events(), { devicePixelRatio: 2, getComputedStyle: () => ({ width: '1000px', height: '1000px', writingMode: 'horizontal-tb', boxSizing: 'content-box' }), VideoDecoder: Decoder, EncodedVideoChunk: class {}, PointerEvent: class {}, ResizeObserver: class { observe() {} } });
  window.top = window.self = window;
  const origin = embedded ? 'https://tesla.mark4z.asia:9999' : 'https://mark4z.github.io';
  Object.assign(globalThis, { document, window, location: { protocol: 'https:', origin,
    search: '?endpoint=wss://untrusted.invalid&ip=8.8.8.8&port=443', hash: '#ws://untrusted.invalid' }, isSecureContext: true,
    VideoDecoder: Decoder, EncodedVideoChunk: window.EncodedVideoChunk, WebSocket: Socket, ResizeObserver: window.ResizeObserver,
    devicePixelRatio: 1,
    fetch: () => { fetches++; throw new Error('The viewer must not fetch an HTTP health probe.'); },
    requestAnimationFrame: callback => { raf.set(++rafId, callback); return rafId; }, cancelAnimationFrame: id => raf.delete(id) });
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { userAgent: embedded ? 'Tesla Chromium/130.0.0.0' : 'Chrome/154.0.0.0' } });
  const paint = () => { const callbacks = [...raf.values()]; raf.clear(); callbacks.forEach(callback => callback()); };
  const pointer = (pointerId, clientX = 500, clientY = 500) => ({ pointerId, clientX, clientY, pointerType: 'touch', buttons: 1 });
  await import(`../viewer.mjs?ui-test-${embedded}`);

  assert.equal(sockets.length, embedded ? 1 : 0, 'only the built-in page connects on load without fresh parked confirmation');
  assert.equal(elements.parked.checked, false, 'restored parked state is not fresh consent');
  assert.equal(elements.origin.textContent, origin);
  assert.equal(elements['manual-endpoint'].hidden, embedded);
  assert.equal(elements['local-endpoint'].hidden, !embedded);
  assert.equal(elements.ip.required, !embedded);
  assert.equal(elements.port.required, !embedded);
  if (embedded) {
    assert.equal(elements['local-endpoint-value'].textContent, 'wss://tesla.mark4z.asia:9999/carplay');
    assert.equal(elements.ip.value, '');
    assert.equal(elements.port.value, '');
    assert.equal(elements.ip.disabled, true);
    assert.equal(elements.port.disabled, true);
    assert.equal(elements['lan-diagnostics'].hidden, true);
    assert.match(elements['transport-warning'].textContent, /TLS/);
  }
  assert.match(elements['connection-transport'].textContent, /Page: HTTPS\. Secure context: yes/);
  assert.equal(elements['connection-timeline'].children.length, embedded ? 1 : 0);
  elements.connection.dispatch('submit');
  assert.equal(sockets.length, embedded ? 1 : 0);

  if (!embedded) {
  elements.parked.checked = true;
  elements.parked.dispatch('change');
  elements.ip.value = 'ws://192.168.1.20:8765/carplay';
  elements.port.value = '8765';
  elements.connection.dispatch('submit');
  assert.equal(sockets.length, 0, 'full endpoints cannot be supplied as the IP');
  assert.equal(elements['connection-timeline'].children.length, 0, 'invalid input is not a network attempt');
  assert.match(elements.status.textContent, /only the private IPv4/);
  elements.ip.value = '192.168.1.20';
  elements.port.value = '8765';
  } else {
    elements.ip.value = 'untrusted.invalid'; // Even tampered hidden inputs are ignored.
    elements.port.value = '443';
  }
  elements.connection.dispatch('submit');
  elements.connection.dispatch('submit');
  assert.equal(sockets.length, 1, 'repeated submit cannot duplicate connection');
  assert.equal(elements.ip.disabled, true);
  assert.match(elements['connection-attempt'].textContent, /Attempt 1/);
  assert.match(elements['connection-transport'].textContent, embedded
    ? /wss:\/\/ \(TLS WebSocket\)/ : /ws:\/\/ \(plaintext LAN WebSocket\)/);
  assert.doesNotMatch(elements['connection-transport'].textContent, /192\.168/);
  const socket = sockets[0];
  assert.equal(socket.url, embedded ? 'wss://tesla.mark4z.asia:9999/carplay' : 'ws://192.168.1.20:8765/carplay');
  socket.open();
  assert.deepEqual(socket.sent, [{ type: 'requestApproval', version: 2 }]);
  socket.receive({ type: 'approvalPending', version: 2 });
  assert.match(elements.status.textContent, /Tap Accept/);
  socket.receive({ type: 'authenticated', version: 2 });
  if (embedded) {
    const settleResolution = () => {
      for (const [id, timer] of [...timers]) if (timer.delay === 2000) { timers.delete(id); timer.callback(); }
    };
    settleResolution();
    const request = socket.sent.find(message => message.type === 'setBrowserResolution');
    assert.deepEqual(request, { type: 'setBrowserResolution', requestId: 1, enabled: true, width: 2000, height: 2000, units: 'device-pixels' });
    socket.receive({ ...request, type: 'browserResolution', width: 1080, height: 1080, effectiveWidth: 864, effectiveHeight: 864, applies: 'unchanged' });
    assert.equal(elements['resolution-target'].textContent, '864 × 864 px');
    elements['controls-grip'].dispatch('click');
    elements['controls-reveal'].dispatch('click');
    window.dispatch('resize');
    settleResolution();
    assert.equal(socket.sent.filter(message => message.type === 'setBrowserResolution').length, 1,
      'unchanged video area and toolbar overlays must not restart CarPlay');
  }
  socket.receive({ type: 'config', streamId: 1, codec: 'avc1.64001f', width: 1280, height: 720 });
  await Promise.resolve();
  const superseded = decoders[0].emit();
  const rendered = decoders[0].emit();
  assert.equal(superseded.closes, 1);
  assert.equal(rendered.closes, 0);
  paint();
  assert.equal(rendered.closes, 1);
  assert.equal(draws.length, 1);
  assert.equal(elements['resolution-actual'].textContent, '1920 × 1080 px', 'actual size comes from the decoded frame, not config, target, or canvas');
  assert.equal(elements.placeholder.hidden, true);
  assert.deepEqual(elements['connection-timeline'].children.map(item => item.textContent.replace(/^\+\d+\.\ds · /, '')),
    ['Connect requested', 'WebSocket opened', 'Android approval pending', 'Approved on Android', 'First video decoded']);
  elements.video.dispatch('pointerdown', pointer(100));
  assert.equal(socket.sent.some(m => m.type === 'touch'), false, 'automatic touch still requires Android ownership acknowledgment');

  assert.deepEqual(socket.sent.at(-1), { type: 'setTouchOwnership', enabled: true, streamId: 1, requestId: 1 });
  elements.video.dispatch('pointerdown', pointer(100));
  assert.equal(socket.sent.some(m => m.type === 'touch'), false, 'automatic request alone does not grant touch ownership');
  assert.equal(elements.video.classes.has('touch-enabled'), false);
  assert.match(elements['touch-status'].textContent, /Waiting for Android/);
  socket.receive({ type: 'touchOwnership', enabled: true, streamId: 1, requestId: 1 });
  assert.equal(elements.video.classes.has('touch-enabled'), true);
  elements.video.dispatch('pointerdown', pointer(100, 500, 100));
  assert.equal(socket.sent.some(m => m.type === 'touch'), false, 'black bars are not touch targets');
  elements.video.dispatch('pointerdown', pointer(100, 250, 500));
  elements.video.dispatch('pointerdown', pointer(200, 750, 500));
  assert.deepEqual(socket.sent.at(-1).contacts, [{ id: 0, x: .25, y: .5 }, { id: 1, x: .75, y: .5 }]);
  assert.equal(socket.sent.at(-1).streamId, 1);
  elements.video.dispatch('pointerdown', pointer(300));
  assert.equal(socket.sent.at(-1).contacts.length, 2, 'extra contact is ignored');
  elements.video.dispatch('pointerup', pointer(100));
  assert.deepEqual(socket.sent.at(-1).contacts, [{ id: 1, x: .75, y: .5 }]);
  elements.video.dispatch('pointermove', pointer(200, 2000, 2000));
  paint();
  assert.deepEqual(socket.sent.at(-1).contacts, [{ id: 1, x: 1, y: 1 }]);
  elements.video.dispatch('pointercancel', pointer(200));
  assert.deepEqual(socket.sent.at(-1).contacts, []);
  assert.equal(captured.size, 0);

  // CSS can stretch the previous bitmap between paints. Hit testing must follow
  // that actual picture, not assume it has already been fitted to the new size.
  elements.video.bounds = { left: 40, top: 20, width: 1000, height: 500 };
  elements.video.dispatch('pointerdown', pointer(350, 540, 70));
  assert.deepEqual(socket.sent.at(-1).contacts, [], 'the stretched bitmap still has black bars');
  elements.video.dispatch('pointerdown', pointer(350, 540, 270));
  assert.deepEqual(socket.sent.at(-1).contacts, [{ id: 0, x: .5, y: .5 }]);
  elements.video.dispatch('lostpointercapture', pointer(350));
  assert.deepEqual(socket.sent.at(-1).contacts, []);
  elements.video.bounds = { left: 0, top: 0, width: 1000, height: 1000, right: 1000, bottom: 1000 };

  elements.video.dispatch('pointerdown', pointer(400));
  window.dispatch('blur');
  assert.deepEqual(socket.sent.at(-1).contacts, []);
  assert.equal(captured.size, 0);
  elements.video.dispatch('pointerdown', pointer(450));
  assert.equal(captured.size, 1);
  elements['viewer-settings'].dispatch('toggle');
  assert.equal(captured.size, 0, 'opening settings releases held contacts');
  assert.deepEqual(socket.sent.at(-1).contacts, []);

  elements.video.dispatch('pointerdown', pointer(451));
  socket.receive({ type: 'config', streamId: 2, codec: 'avc1.64001f', width: 1920, height: 1080 });
  assert.equal(captured.size, 0, 'reconfiguration releases held gestures');
  assert.equal(elements.video.classes.has('touch-enabled'), false);
  socket.receive({ type: 'touchOwnership', enabled: true, streamId: 1, requestId: 1 });
  await Promise.resolve();
  decoders.at(-1).emit();
  paint();
  assert.equal(elements.video.classes.has('touch-enabled'), false, 'new generation still needs a matching ACK');
  const resumedTouch = socket.sent.at(-1);
  assert.equal(resumedTouch.enabled, true);
  assert.equal(resumedTouch.streamId, 2);
  assert.equal(resumedTouch.requestId, 3);
  socket.receive({ ...resumedTouch, type: 'touchOwnership' });
  assert.equal(elements.video.classes.has('touch-enabled'), true);
  const cancelled = decoders.at(-1).emit();
  document.visibilityState = 'hidden';
  document.dispatch('visibilitychange');
  assert.equal(cancelled.closes, 1, 'pending frame is closed on hide');
  assert.equal(elements['resolution-actual'].textContent, '等待视频');
  if (embedded) assert.equal(elements['resolution-target'].textContent, '等待 Android 确认');
  assert.equal(socket.readyState, 3);
  assert.equal(elements.placeholder.hidden, false);
  assert.equal(elements.parked.checked, false);
  document.visibilityState = 'visible';
  document.dispatch('visibilitychange');
  window.dispatch('pageshow', { persisted: true });
  assert.equal(sockets.length, embedded ? 2 : 1, 'only embedded viewer resumes automatically without new parked confirmation');
  if (!embedded) { elements.parked.checked = true; elements.parked.dispatch('change'); }
  assert.equal(sockets.length, 2);
  const current = sockets[1];
  current.open();
  current.receive({ type: 'approvalPending', version: 2 });
  current.onclose({ code: 1006, reason: '' });
  assert.equal(current.readyState, 3);
  const retryTimers = [...timers].filter(([, timer]) => timer.delay === 5000);
  assert.equal(retryTimers.length, 1, 'one retry is pending after network loss');
  const [retryId, retryTimer] = retryTimers[0]; timers.delete(retryId); retryTimer.callback();
  assert.equal(sockets.length, 3);
  const replacement = sockets[2];
  replacement.open();
  replacement.receive({ type: 'approvalPending', version: 2 });
  replacement.onclose({ code: 4001, reason: 'superseded' });
  assert.match(elements.status.textContent, /Another browser/);
  assert.equal([...timers.values()].some(timer => timer.delay === 5000), false, 'superseded viewer never retries');
  document.visibilityState = 'hidden'; document.dispatch('visibilitychange');
  document.visibilityState = 'visible'; document.dispatch('visibilitychange');
  window.dispatch('pageshow', { persisted: true });
  assert.equal(sockets.length, 3, 'focus and BFCache cannot reclaim a superseded session');
  window.dispatch('pagehide');
  assert.equal([...timers.values()].some(timer => timer.delay === 5000), false);
  assert.equal(fetches, 0, 'HTTPS viewer never automatically requests the HTTP health endpoint');
});
}
