import test from 'node:test';
import assert from 'node:assert/strict';
import { browserSize, followBrowserResolution } from '../resolution.mjs';
import { BrowserSession } from '../session.mjs';

function fixture() {
  const sent = [], messages = [], timers = new Map(); let id = 0, size = { width: 1280, height: 720 };
  const follow = followBrowserResolution({ measure: () => size, send: m => { sent.push(m); return true; }, onStatus: m => messages.push(m),
    setTimer: (fn, ms) => { timers.set(++id, { fn, ms }); return id; }, clearTimer: key => timers.delete(key) });
  const tick = ms => { for (const [key, job] of [...timers]) if (job.ms === ms) { timers.delete(key); job.fn(); } };
  const ack = fields => follow.acknowledged({ ...sent.at(-1), applies: 'unchanged', ...fields });
  return { follow, sent, messages, timers, tick, ack, resize(width, height) { size = { width, height }; follow.changed(); } };
}

test('only approved, two-second stable CSS viewport triggers a report; duplicate and ACK loops are suppressed', () => {
  const p = fixture(); p.follow.changed(); p.tick(2000); assert.equal(p.sent.length, 0);
  p.follow.connected(true); p.resize(1200, 700); p.resize(1280, 720);
  assert.equal(p.timers.size, 1); p.tick(2000);
  assert.deepEqual(p.sent[0], { requestId: 1, enabled: true, width: 1280, height: 720 });
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
  const request = { requestId: 1, enabled: true, width: 1280, height: 720 };
  assert.equal(session.setBrowserResolution(request), false);
  session.closed = false; session.authenticated = true; session.socket = { readyState: 1, bufferedAmount: 0, send: raw => sent.push(JSON.parse(raw)) };
  assert.equal(session.setBrowserResolution(request), true); assert.equal(sent[0].type, 'setBrowserResolution');
  assert.equal(session.setBrowserResolution({ ...request, width: Infinity }), false);
  const ack = { ...request, type: 'browserResolution', applies: 'unchanged', text: 'untrusted payload' };
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
