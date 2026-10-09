import test from 'node:test';
import assert from 'node:assert/strict';
import { browserSize, followBrowserResolution, renderPixelSize, observeRenderPixels } from '../resolution.mjs';
import { BrowserSession } from '../session.mjs';

function fixture() {
  const sent = [], messages = [], targets = [], timers = new Map(); let id = 0, size = { width: 1280, height: 720 };
  const follow = followBrowserResolution({ measure: () => size, send: m => { sent.push(m); return true; }, onStatus: m => messages.push(m), onTarget: size => targets.push(size),
    setTimer: (fn, ms) => { timers.set(++id, { fn, ms }); return id; }, clearTimer: key => timers.delete(key) });
  const tick = ms => { for (const [key, job] of [...timers]) if (job.ms === ms) { timers.delete(key); job.fn(); } };
  const ack = fields => follow.acknowledged({ ...sent.at(-1), applies: 'unchanged', ...fields });
  return { follow, sent, messages, targets, timers, tick, ack, resize(width, height) { size = { width, height }; follow.changed(); } };
}

test('only approved, two-second stable device-pixel viewport triggers a report; duplicate and ACK loops are suppressed', () => {
  const p = fixture(); p.follow.changed(); p.tick(2000); assert.equal(p.sent.length, 0);
  p.follow.connected(true); p.resize(1200, 700); p.resize(1280, 720);
  assert.equal(p.timers.size, 1); p.tick(2000);
  assert.deepEqual(p.sent[0], { requestId: 1, enabled: true, width: 1280, height: 720, units: 'device-pixels' });
  p.ack({ effectiveWidth: 1024, effectiveHeight: 576 }); p.tick(2000); p.follow.changed(); p.tick(2000); assert.equal(p.sent.length, 1);
  assert.match(p.messages.at(-1), /percentage still applies/);
  assert.match(p.messages.at(-1), /1024 × 576/);
  p.resize(720, 1280); p.tick(2000); assert.equal(p.sent.length, 2);
});

test('timeout does not resend or reconnect; stale ACK and disconnected work cannot apply', () => {
  const p = fixture(); p.follow.connected(true); p.tick(2000); p.tick(10000);
  p.follow.changed(); p.tick(2000); assert.equal(p.sent.length, 1);
  p.ack(); assert.match(p.messages.at(-1), /did not confirm/);
  p.follow.connected(false); p.resize(1600, 900); p.tick(2000); assert.equal(p.sent.length, 1);
  p.follow.connected(true); p.tick(2000); assert.equal(p.sent[1].requestId, 2);
  p.follow.acknowledged({ requestId: 1, enabled: true, width: 1280, height: 720, applies: 'unchanged' });
  assert.equal(p.follow.pending, true); p.ack(); assert.equal(p.follow.pending, false);
});

test('explicit reset suppresses subsequent resize reports and pending toggle cannot change intent', () => {
  const p = fixture(); p.follow.connected(true); p.tick(2000);
  assert.equal(p.follow.enable(false), false); p.ack(); p.resize(1400, 800); p.tick(2000); assert.equal(p.sent.length, 2);
  p.ack(); assert.equal(p.follow.enable(false), true); assert.equal(p.sent.at(-1).enabled, false);
  p.ack({ enabled: false }); p.resize(1800, 900); p.tick(2000); assert.equal(p.sent.length, 3);
});

test('viewport validation is finite, bounded, aspect-limited, and independent of devicePixelRatio', () => {
  assert.deepEqual(browserSize({ width: 1280.2, height: 720 }), { width: 1280, height: 720 });
  for (const size of [{ width: NaN, height: 720 }, { width: 100, height: 100 }, { width: 5000, height: 720 }, { width: 20000, height: 10000 }]) assert.equal(browserSize(size), null);
});

test('session resolution send remains approval-gated; ACK parsing rejects invalid fields and arbitrary text', () => {
  const sent = [], acks = [];
  const session = new BrowserSession({ onState() {}, onFrame() {}, onBrowserResolution: m => acks.push(m) });
  const request = { requestId: 1, enabled: true, width: 1280, height: 720, units: 'device-pixels' };
  assert.equal(session.setBrowserResolution(request), false);
  session.closed = false; session.authenticated = true; session.socket = { readyState: 1, bufferedAmount: 0, send: raw => sent.push(JSON.parse(raw)) };
  assert.equal(session.setBrowserResolution(request), true); assert.equal(sent[0].type, 'setBrowserResolution');
  assert.equal(session.setBrowserResolution({ ...request, width: Infinity }), false);
  const ack = { ...request, type: 'browserResolution', applies: 'unchanged', text: 'untrusted payload' };
  for (const requestId of [undefined, null, 0, -1, 1.5, '1', Number.MAX_SAFE_INTEGER + 1]) {
    assert.equal(session.setBrowserResolution({ ...request, requestId }), false);
    session.receiveText(JSON.stringify({ ...ack, requestId }));
    assert.equal(acks.length, 0, 'invalid request IDs cannot reach the resolution controls');
  }
  session.receiveText(JSON.stringify(ack)); assert.equal(acks.length, 1); assert.equal(acks[0].text, undefined);
  session.receiveText(JSON.stringify({ ...ack, width: '1280' }));
  session.receiveText(JSON.stringify({ ...ack, applies: 'arbitrary server text' })); assert.equal(acks.length, 1);
});

test('same-request reconnect progress is displayed until a newer resize supersedes it', () => {
  const p = fixture(); p.follow.connected(true); p.tick(2000);
  p.ack({ applies: 'nextConnection' });
  p.ack({ applies: 'reconnecting' }); assert.match(p.messages.at(-1), /Reconnecting CarPlay/);
  p.ack({ applies: 'unchanged' }); assert.match(p.messages.at(-1), /already matches/);
  p.resize(1400, 800); p.tick(2000);
  p.follow.acknowledged({ requestId: 1, enabled: true, width: 1280, height: 720, applies: 'nextConnection', code: 'reconnectFailed' });
  assert.match(p.messages.at(-1), /Waiting for Android/);
  p.ack({ code: 'reconnectFailed' }); assert.match(p.messages.at(-1), /manual connection control/);
});


test('device content box is already in pixels; fallback multiplies fractional CSS by DPR once', () => {
  assert.deepEqual(renderPixelSize({ width: 960, height: 540 }, 2), { width: 1920, height: 1080 });
  assert.deepEqual(renderPixelSize({ width: 960.4, height: 540.4 }, 1.25), { width: 1201, height: 676 });
  assert.deepEqual(renderPixelSize({ width: 960, height: 540 }, 2,
    [{ inlineSize: 1921, blockSize: 1081 }]), { width: 1921, height: 1081 });
  assert.deepEqual(renderPixelSize({ width: 960, height: 540 }, 2,
    [{ inlineSize: 1081, blockSize: 1921 }], 'vertical-rl'), { width: 1921, height: 1081 });
  assert.deepEqual(renderPixelSize({ width: 960, height: 540 }, 2,
    [{ inlineSize: 1080, blockSize: 1920 }], 'sideways-lr'), { width: 1920, height: 1080 });
  for (const dpr of [NaN, Infinity, 0, -1, 17]) assert.equal(renderPixelSize({ width: 960, height: 540 }, dpr), null);
  assert.equal(renderPixelSize({ width: 0, height: 540 }, 2), null);
  assert.equal(renderPixelSize({ width: 960, height: 540 }, 2, [{ inlineSize: 20000, blockSize: 10000 }]), null);
});

test('observer measures content box, invalidates exact pixels on DPR-only changes, and falls back on unsupported box', () => {
  const element = {}; let callback, mediaChanged, count = 0; const boxes = [];
  const css = { width: '980px', height: '560px', boxSizing: 'border-box', paddingLeft: '8px', paddingRight: '8px',
    paddingTop: '8px', paddingBottom: '8px', borderLeftWidth: '2px', borderRightWidth: '2px', borderTopWidth: '2px', borderBottomWidth: '2px', writingMode: 'horizontal-tb' };
  const env = { devicePixelRatio: 2, getComputedStyle: () => css,
    ResizeObserver: class { constructor(fn) { callback = fn; } observe(_, options) { boxes.push(options.box); if (options.box === 'device-pixel-content-box') throw Error('unsupported'); } },
    matchMedia: () => ({ addEventListener: (_, fn) => { mediaChanged = fn; }, removeEventListener() {} }) };
  const measure = observeRenderPixels(element, () => count++, env);
  assert.deepEqual(boxes, ['device-pixel-content-box', 'content-box']);
  assert.deepEqual(measure(), { width: 1920, height: 1080 });
  callback([{ target: element, contentRect: { width: 960, height: 540 }, devicePixelContentBoxSize: [{ inlineSize: 1921, blockSize: 1081 }] }]);
  assert.deepEqual(measure(), { width: 1921, height: 1081 });
  env.devicePixelRatio = 1.5; mediaChanged();
  assert.deepEqual(measure(), { width: 1440, height: 810 }); assert.equal(count, 2);
  callback([{ target: element, contentRect: { width: 960, height: 540 } }]);
  assert.deepEqual(measure(), { width: 1440, height: 810 });
  css.width = '1000px'; assert.deepEqual(measure(), { width: 1470, height: 810 });
});

test('resolution wire units are explicit and no DPR is sent for Android to apply twice', () => {
  const sent = [], acks = [];
  const session = new BrowserSession({ onState() {}, onFrame() {}, onBrowserResolution: m => acks.push(m) });
  session.closed = false; session.authenticated = true;
  session.socket = { readyState: 1, bufferedAmount: 0, send: raw => sent.push(JSON.parse(raw)) };
  const request = { requestId: 1, enabled: true, width: 1920, height: 1080 };
  assert.equal(session.setBrowserResolution(request), false);
  assert.equal(session.setBrowserResolution({ ...request, units: 'css-pixels' }), false);
  assert.equal(session.setBrowserResolution({ ...request, units: 'device-pixels', dpr: 2 }), true);
  assert.equal(sent[0].dpr, undefined); assert.equal(sent[0].width, 1920);
  session.receiveText(JSON.stringify({ ...request, type: 'browserResolution', applies: 'unchanged' }));
  assert.equal(acks.length, 0);
});


test('a hidden container never reports its retained computed CSS dimensions', () => {
  let visible = false;
  const element = { getClientRects: () => visible ? [{}] : [] };
  const measure = observeRenderPixels(element, () => {}, { devicePixelRatio: 2,
    getComputedStyle: () => ({ width: '960px', height: '540px', boxSizing: 'content-box' }) });
  assert.equal(measure(), null);
  visible = true; assert.deepEqual(measure(), { width: 1920, height: 1080 });
});

test('target metrics use only correlated effective Android dimensions and never cause resize feedback', () => {
  const p = fixture(); p.follow.connected(true); p.tick(2000);
  p.ack({ effectiveWidth: 1024, effectiveHeight: 576, applies: 'reconnecting' });
  assert.deepEqual(p.targets.at(-1), { width: 1024, height: 576 });
  const count = p.targets.length;
  p.follow.acknowledged({ requestId: 999, effectiveWidth: 333, effectiveHeight: 333 });
  assert.equal(p.targets.length, count);
  p.tick(2000);
  assert.equal(p.sent.length, 1, 'rendering effective dimensions cannot emit a new viewport request');
  p.ack({ effectiveWidth: 1024, effectiveHeight: 576, code: 'reconnectFailed' });
  assert.deepEqual(p.targets.at(-1), { width: 1024, height: 576 }, 'saved target remains truthful even if current video differs');
  p.resize(1440, 900); p.tick(2000);
  assert.equal(p.targets.at(-1), null, 'a new request invalidates old target labels');
  p.ack({ effectiveWidth: 1152, effectiveHeight: 720 });
  assert.deepEqual(p.targets.at(-1), { width: 1152, height: 720 });
  p.follow.connected(false);
  assert.equal(p.targets.at(-1), null);
  p.ack({ effectiveWidth: 1152, effectiveHeight: 720 });
  assert.equal(p.targets.at(-1), null, 'late ACK after disconnect cannot restore target');
});

test('missing effective dimensions or failed save never labels raw browser dimensions as target output', () => {
  const p = fixture(); p.follow.connected(true); p.tick(2000);
  p.ack(); assert.equal(p.targets.at(-1), null);
  p.resize(1400, 800); p.tick(2000);
  p.ack({ effectiveWidth: 1120, effectiveHeight: 640, code: 'saveFailed' });
  assert.equal(p.targets.at(-1), null);
});


test('high-DPR browser measurement sends full rendering pixels without a client quality cap', () => {
  assert.deepEqual(renderPixelSize({ width: 1600, height: 1068 }, 2), { width: 3200, height: 2136 });
  assert.deepEqual(renderPixelSize({ width: 1068, height: 1600 }, 2), { width: 2136, height: 3200 });
  const p = fixture(); p.resize(3200, 2136); p.follow.connected(true); p.tick(2000);
  assert.deepEqual(p.sent[0], { requestId: 1, enabled: true, width: 3200, height: 2136, units: 'device-pixels' });
});
