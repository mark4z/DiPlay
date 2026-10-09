import test from 'node:test';
import assert from 'node:assert/strict';
import { deferred, flush } from './async-fixtures.mjs';

let testId = 0;
async function page(t, { hidden = false, fullscreen = 'resolve' } = {}) {
  class Events {
    constructor() { this.listeners = new Map(); }
    addEventListener(name, handler) {
      if (!this.listeners.has(name)) this.listeners.set(name, []);
      this.listeners.get(name).push(handler);
    }
    dispatch(name, data = {}) { for (const handler of this.listeners.get(name) || []) handler({ preventDefault() {}, ...data }); }
  }
  class Element extends Events {
    constructor() {
      super(); this.style = {}; this.attributes = {}; this.checked = false; this.value = ''; this.hidden = false; this.dataset = {}; this.children = [];
      this.classes = new Set();
      this.classList = { remove: name => this.classes.delete(name), toggle: (name, value) => value ? this.classes.add(name) : this.classes.delete(name) };
    }
    contains(element) { return element === this || this.children.includes(element); }
    setAttribute(name, value) { this.attributes[name] = value; }
    removeAttribute(name) { delete this.attributes[name]; }
    getContext() { return { clearRect() {} }; }
    getBoundingClientRect() { return { left: 12, top: 12, right: 1000, bottom: 700, width: 500, height: 60 }; }
    append(...elements) { this.children.push(...elements); }
    replaceChildren(...elements) { this.children = elements; }
  }
  const elements = new Map();
  const element = id => { if (!elements.has(id)) elements.set(id, new Element()); return elements.get(id); };
  const document = Object.assign(new Events(), { visibilityState: hidden ? 'hidden' : 'visible', fullscreenElement: null,
    getElementById: element, createElement: () => new Element() });
  let fullscreenCalls = 0;
  const full = deferred();
  if (fullscreen !== 'unavailable') element('viewer-shell').requestFullscreen = () => {
    fullscreenCalls++;
    if (fullscreen === 'throw') throw new Error('Blocked');
    if (fullscreen === 'reject') return Promise.reject(new Error('Blocked'));
    if (fullscreen === 'pending') return full.promise;
    document.fullscreenElement = element('viewer-shell'); document.dispatch('fullscreenchange'); return Promise.resolve();
  };
  const sockets = [], timers = new Map(); let timerId = 0;
  t.mock.method(globalThis, 'setTimeout', (callback, delay) => { timers.set(++timerId, { callback, delay }); return timerId; });
  t.mock.method(globalThis, 'clearTimeout', id => timers.delete(id));
  class Socket {
    constructor(url) { this.url = url; this.sent = []; this.readyState = 0; this.bufferedAmount = 0; sockets.push(this); }
    open() { this.readyState = 1; this.onopen?.(); }
    receive(message) { this.onmessage?.({ data: JSON.stringify(message) }); }
    send(message) { this.sent.push(JSON.parse(message)); }
    close() { this.readyState = 3; }
  }
  const window = Object.assign(new Events(), { VideoDecoder: class {}, EncodedVideoChunk: class {}, PointerEvent: class {} });
  window.self = window.top = window;
  Object.assign(globalThis, { document, window, location: { protocol: 'https:', origin: 'https://tesla.mark4z.asia:9999' },
    isSecureContext: true, VideoDecoder: window.VideoDecoder, EncodedVideoChunk: window.EncodedVideoChunk,
    WebSocket: Socket });
  Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { userAgent: 'Tesla Chromium/130.0' } });
  await import(`../viewer.mjs?entry-test-${++testId}`);
  const approve = () => { const socket = sockets.at(-1); socket.open(); socket.receive({ type: 'approvalPending', version: 2 }); socket.receive({ type: 'authenticated', version: 2 }); return socket; };
  return { element, document, window, sockets, timers, approve, full, fullscreenCalls: () => fullscreenCalls };
}

test('embedded entry starts exactly once, keeps approval gates, and requests fullscreen only from a real gesture', async t => {
  const p = await page(t, { fullscreen: 'pending' });
  assert.equal(p.sockets.length, 1);
  assert.equal(p.sockets[0].url, 'wss://tesla.mark4z.asia:9999/carplay');
  assert.equal(p.element('parked').checked, false, 'no fabricated checkbox consent');
  assert.equal(p.element('parked-control').hidden, true);
  assert.equal(p.element('viewer-shell').classes.has('embedded-viewer'), true);
  assert.equal(p.element('settings-content').children.length, 6);
  p.element('enter').dispatch('click');
  assert.equal(p.fullscreenCalls(), 0, 'preapproval click cannot enter fullscreen');
  const socket = p.approve();
  assert.deepEqual(socket.sent, [{ type: 'requestApproval', version: 2 }]);
  assert.equal(p.fullscreenCalls(), 0, 'approval alone must not manufacture a fullscreen gesture');
  assert.equal(p.element('enter').textContent, 'Enter fullscreen');
  p.element('enter').dispatch('click');
  assert.equal(p.fullscreenCalls(), 1);
  assert.deepEqual(socket.sent, [{ type: 'requestApproval', version: 2 }], 'fullscreen does not send media-routing controls');
  p.element('enter').dispatch('click');
  p.element('connection').dispatch('submit');
  p.element('retry').dispatch('click');
  assert.equal(p.fullscreenCalls(), 1, 'pending fullscreen cannot duplicate requests');
  assert.equal(p.sockets.length, 1);
  p.element('stop').dispatch('click');
  p.full.reject(new Error('late failure'));
  await flush();
  assert.equal(p.element('entry-status').hidden, true, 'stale fullscreen result cannot restore an old error');
  assert.equal(p.element('retry').hidden, false);
  assert.equal(p.sockets.length, 1, 'Stop never causes a retry');
  p.element('retry').dispatch('click');
  p.element('retry').dispatch('click');
  assert.equal(p.sockets.length, 2, 'one explicit retry makes one fresh attempt');
  p.sockets[1].open();
  assert.deepEqual(p.sockets[1].sent, [{ type: 'requestApproval', version: 2 }]);
  p.element('stop').dispatch('click');
});

test('successful fullscreen hides Enter until fullscreen exits without changing the session', async t => {
  const p = await page(t);
  const socket = p.approve();
  assert.equal(p.element('enter').hidden, false);
  p.element('enter').dispatch('click');
  assert.equal(p.fullscreenCalls(), 1, 'request occurs within the click');
  await flush();
  assert.equal(p.element('enter').hidden, true);
  p.element('enter').dispatch('click');
  assert.equal(p.fullscreenCalls(), 1, 'already-fullscreen gesture does not repeat the request');
  p.document.fullscreenElement = null;
  p.document.dispatch('fullscreenchange');
  assert.equal(p.element('enter').hidden, false);
  assert.equal(p.element('enter').disabled, false);
  assert.equal(p.element('enter').textContent, 'Enter fullscreen');
  assert.equal(socket.readyState, 1);
  assert.deepEqual(socket.sent, [{ type: 'requestApproval', version: 2 }]);
  p.element('stop').dispatch('click');
});

for (const fullscreen of ['unavailable', 'reject', 'throw']) {
  test(`fullscreen ${fullscreen} leaves video connection intact and exposes a truthful fallback`, async t => {
    const p = await page(t, { fullscreen });
    const socket = p.approve();
    p.element('enter').dispatch('click');
    await flush();
    assert.equal(p.element('entry-status').hidden, false);
    assert.match(p.element('entry-status').textContent, /fullscreen is unavailable.*fills this page/);
    assert.equal(socket.readyState, 1);
    assert.equal(p.sockets.length, 1);
    p.element('stop').dispatch('click');
  });
}

test('hidden opening, tab restoration, and BFCache restoration never trigger automatic retries', async t => {
  const p = await page(t, { hidden: true });
  assert.equal(p.sockets.length, 0, 'background page cannot open a socket');
  p.document.visibilityState = 'visible'; p.document.dispatch('visibilitychange');
  p.window.dispatch('pageshow', { persisted: true });
  p.window.dispatch('pageshow', { persisted: true });
  assert.equal(p.sockets.length, 0);
  p.element('retry').dispatch('click'); p.approve();
  p.document.visibilityState = 'hidden'; p.document.dispatch('visibilitychange');
  p.document.visibilityState = 'visible'; p.document.dispatch('visibilitychange');
  p.window.dispatch('pageshow', { persisted: true });
  assert.equal(p.sockets.length, 1);
  assert.equal(p.sockets[0].readyState, 3);
});

test('approval rejection and connection timeout stay visible with explicit retry only', async t => {
  const p = await page(t);
  p.sockets[0].open();
  p.sockets[0].receive({ type: 'approvalPending', version: 2 });
  p.sockets[0].receive({ type: 'error', version: 2, code: 'approvalRejected' });
  assert.equal(p.element('display-toolbar').dataset.state, 'error');
  assert.match(p.element('status').textContent, /rejected on Android/);
  assert.equal(p.element('retry').hidden, false);
  assert.equal(p.sockets.length, 1);
  p.element('retry').dispatch('click');
  const timer = [...p.timers.values()].find(value => value.delay === 60000);
  assert.ok(timer); timer.callback();
  assert.match(p.element('status').textContent, /Connection timed out/);
  assert.equal(p.element('retry').hidden, false);
  assert.equal(p.sockets.length, 2);
  assert.equal(p.sockets[1].readyState, 3);
});
