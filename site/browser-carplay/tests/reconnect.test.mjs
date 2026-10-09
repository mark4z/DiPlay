import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { AutoReconnect } from '../session.mjs';

function fixture(connectImpl = () => {}) {
  const timers = new Map(); let timerId = 0, calls = 0;
  const state = { eligible: true, closed: true, blocked: false };
  const reconnect = new AutoReconnect({
    connect: () => { calls++; connectImpl(state, reconnect); },
    eligible: () => state.eligible, closed: () => state.closed, blocked: () => state.blocked,
    setTimer: (callback, delay) => { timers.set(++timerId, { callback, delay }); return timerId; },
    clearTimer: id => timers.delete(id),
  });
  const fire = () => {
    assert.equal(timers.size, 1); const [id, timer] = [...timers][0]; timers.delete(id);
    assert.equal(timer.delay, 5000); timer.callback();
  };
  return { state, timers, reconnect, fire, calls: () => calls };
}

test('synchronous connection failure schedules one retry without recursion or duplicate timers', () => {
  const h = fixture((state, reconnect) => reconnect.update());
  h.reconnect.update(true);
  assert.equal(h.calls(), 1);
  assert.equal(h.timers.size, 1);
  for (let i = 0; i < 30; i++) h.reconnect.update();
  assert.equal(h.timers.size, 1);
  h.fire();
  assert.equal(h.calls(), 2);
  assert.equal(h.timers.size, 1);
  h.reconnect.suspend();
  assert.equal(h.timers.size, 0);
});

test('active and pending-approval sockets never get a retry timer', () => {
  const h = fixture(state => { state.closed = false; });
  h.reconnect.update(true);
  for (let i = 0; i < 30; i++) h.reconnect.resume();
  assert.equal(h.calls(), 1);
  assert.equal(h.timers.size, 0);
  h.state.closed = true;
  h.reconnect.update();
  assert.equal(h.timers.size, 1);
  h.fire();
  assert.equal(h.calls(), 2);
  assert.equal(h.timers.size, 0);
});

test('supersession or rejection cancels scheduled retry and survives suspend/resume', () => {
  const h = fixture();
  h.reconnect.update();
  const stale = [...h.timers.values()][0].callback;
  h.state.blocked = true;
  h.reconnect.update();
  h.reconnect.suspend(); h.reconnect.resume(); stale();
  assert.equal(h.calls(), 0);
  assert.equal(h.timers.size, 0);
});

test('backgrounding clears retry and a queued callback cannot connect a hidden page', () => {
  const h = fixture();
  h.reconnect.update();
  const stale = [...h.timers.values()][0].callback;
  h.state.eligible = false;
  h.reconnect.suspend(); stale();
  assert.equal(h.calls(), 0);
  assert.equal(h.timers.size, 0);
  h.state.eligible = true;
  h.reconnect.resume();
  assert.equal(h.calls(), 1);
  h.reconnect.suspend();
});

test('canonical viewer has no connection or touch toggle buttons and preserves fullscreen/settings', () => {
  const html = readFileSync(new URL('../index.html', import.meta.url), 'utf8');
  assert.doesNotMatch(html, /id="(?:touch|connect|disconnect|retry|stop)"/);
  for (const id of ['enter', 'viewer-settings', 'settings-close', 'controls-grip', 'resolution-follow']) assert.match(html, new RegExp(`id="${id}"`));
});

test('an already queued cancelled callback cannot erase a newer retry timer', () => {
  const h = fixture();
  h.reconnect.update();
  const old = [...h.timers.values()][0].callback;
  h.reconnect.suspend(); h.reconnect.resume();
  assert.equal(h.calls(), 1);
  old(); h.reconnect.update();
  assert.equal(h.calls(), 1);
  assert.equal(h.timers.size, 1);
  h.fire();
  assert.equal(h.calls(), 2);
  h.reconnect.suspend();
});

test('flat styling preserves video bounds, touch targets, keyboard focus and bounded small-screen settings', () => {
  const css = readFileSync(new URL('../viewer.css', import.meta.url), 'utf8');
  assert.doesNotMatch(css, /(?:linear|radial)-gradient|backdrop-filter|text-shadow/);
  for (const [, shadow] of css.matchAll(/box-shadow:([^;]+);/g)) assert.equal(shadow.trim(), 'none');
  assert.match(css, /button\s*\{[^}]*min-height:\s*46px/);
  assert.match(css, /:focus-visible\s*\{[^}]*outline:/);
  assert.match(css, /canvas\.touch-enabled\s*\{[^}]*touch-action:\s*none/);
  assert.match(css, /\.embedded-viewer #viewport\s*\{[^}]*width:\s*100%;[^}]*height:\s*100%/);
  assert.match(css, /#settings-content\s*\{[^}]*overflow:\s*auto/);
  assert.match(css, /\.embedded-viewer #settings-content\s*\{[^}]*position:\s*fixed;[^}]*width:\s*min\(560px,[^}]*max-height:\s*calc\(100dvh/);
  assert.match(css, /@media\s*\(max-width:\s*520px\)/);
});
