import test from 'node:test';
import assert from 'node:assert/strict';
import { clampPosition, floatingControls } from '../controls.mjs';

function fixture() {
  class Element {
    constructor() { this.events = {}; this.style = {}; this.attributes = {}; this.hidden = false; this.open = false; this.capture = null; }
    addEventListener(name, fn) { (this.events[name] ||= []).push(fn); }
    emit(name, fields = {}) { const event = { preventDefault() {}, pointerId: 1, clientX: 900, clientY: 30, pointerType: 'touch', ...fields }; for (const fn of this.events[name] || []) fn(event); }
    setAttribute(k, v) { this.attributes[k] = v; }
    removeAttribute(k) { delete this.attributes[k]; }
    contains(node) { return node === this || node === grip; }
    getBoundingClientRect() { return this.rect || { width: 300, height: 60 }; }
    setPointerCapture(id) { this.capture = id; }
    hasPointerCapture(id) { return this.capture === id; }
    releasePointerCapture(id) { this.capture = null; this.emit('lostpointercapture', { pointerId: id }); }
    focus() { document.activeElement = this; }
  }
  const panel = new Element(), grip = new Element(), reveal = new Element(), settings = new Element(), safeArea = new Element();
  safeArea.rect = { left: 12, top: 24, right: 988, bottom: 688 };
  const document = Object.assign(new Element(), { visibilityState: 'visible', activeElement: null });
  const window = new Element(), timers = new Map(); let id = 0;
  const controller = floatingControls({ panel, grip, reveal, settings, safeArea, document, window,
    setTimer: (fn, ms) => { timers.set(++id, { fn, ms }); return id; }, clearTimer: key => timers.delete(key) });
  const tick = () => { const jobs = [...timers.values()]; timers.clear(); for (const job of jobs) job.fn(); };
  return { panel, grip, reveal, settings, safeArea, document, window, timers, controller, tick };
}

test('idle hides only healthy unfocused closed chrome after four seconds; explicit reveal remains accessible', () => {
  const p = fixture(); p.controller.update({ critical: false });
  assert.equal([...p.timers.values()][0].ms, 4000);
  p.tick(); assert.equal(p.panel.hidden, true); assert.equal(p.reveal.hidden, false);
  p.reveal.emit('click'); assert.equal(p.panel.hidden, false); assert.equal(p.document.activeElement, p.grip);
  p.tick(); assert.equal(p.panel.hidden, false, 'never hide focused controls');
  p.document.activeElement = null; p.panel.emit('focusout'); p.tick(); p.tick(); assert.equal(p.panel.hidden, true);
  p.controller.update({ critical: true }); assert.equal(p.panel.hidden, false); assert.equal(p.timers.size, 0);
  p.controller.update({ critical: false }); p.settings.open = true; p.settings.emit('toggle');
  assert.equal(p.timers.size, 0); p.tick(); assert.equal(p.panel.hidden, false);
});

test('drag captures only grip pointer, ignores other contacts and clamps; cancel/lost capture/blur release', () => {
  const p = fixture(); p.controller.update({ critical: false });
  p.grip.emit('pointerdown'); assert.equal(p.grip.capture, 1); assert.equal(p.timers.size, 0);
  p.grip.emit('pointermove', { pointerId: 2, clientX: 0 }); assert.equal(p.panel.style.left, '688px');
  p.grip.emit('pointermove', { clientX: -1000, clientY: 5000 }); assert.equal(p.panel.style.left, '12px'); assert.equal(p.panel.style.top, '628px');
  p.grip.emit('pointercancel'); assert.equal(p.grip.capture, null); assert.equal(p.timers.size, 1);
  p.grip.emit('pointerdown'); p.grip.emit('lostpointercapture'); assert.equal(p.timers.size, 1);
  p.grip.emit('pointerdown'); p.window.emit('blur'); assert.equal(p.grip.capture, null); assert.equal(p.timers.size, 0);
});

test('keyboard and orientation changes retain safe-area bounds, including oversized panels', () => {
  const p = fixture(); p.grip.emit('keydown', { key: 'ArrowDown', shiftKey: true }); assert.equal(p.panel.style.top, '64px');
  p.safeArea.rect = { left: 40, top: 30, right: 240, bottom: 80 }; p.window.emit('resize');
  assert.equal(p.panel.style.left, '40px'); assert.equal(p.panel.style.top, '30px');
  assert.equal(p.panel.style.maxWidth, '200px'); assert.equal(p.panel.style.maxHeight, '50px');
  assert.deepEqual(clampPosition(-9, 900, { left: 12, top: 24, right: 200, bottom: 100 }, { width: 300, height: 200 }), { x: 12, y: 24 });
});

test('capture failure, nonprimary pointers and hidden page cannot leave a live drag or timer', () => {
  const p = fixture(); p.controller.update({ critical: false });
  p.grip.emit('pointerdown', { isPrimary: false }); assert.equal(p.grip.capture, null);
  p.grip.setPointerCapture = () => { throw Error('gone'); }; p.grip.emit('pointerdown'); assert.equal(p.grip.capture, null);
  p.document.visibilityState = 'hidden'; p.document.emit('visibilitychange'); assert.equal(p.timers.size, 0);
  p.document.visibilityState = 'visible'; p.document.emit('visibilitychange'); assert.equal(p.panel.hidden, false);
});


test('touch reveals without consuming or capturing input and keeps chrome out of a multi-contact gesture', () => {
  const p = fixture(); p.controller.update({ critical: false }); p.tick();
  const fail = () => assert.fail('activity observer must not consume the original CarPlay event');
  const contact = { target: {}, preventDefault: fail, stopPropagation: fail, stopImmediatePropagation: fail };
  p.document.emit('pointerdown', contact);
  assert.equal(p.panel.hidden, false);
  assert.equal(p.grip.capture, null);
  assert.equal(p.panel.attributes['data-display-gesture'], 'true');
  assert.equal(p.timers.size, 0);
  p.document.emit('pointerdown', { ...contact, pointerId: 2 });
  p.document.emit('pointerup');
  assert.equal(p.panel.attributes['data-display-gesture'], 'true');
  assert.equal(p.timers.size, 0);
  p.document.emit('pointercancel', { pointerId: 2 });
  assert.equal(p.panel.attributes['data-display-gesture'], undefined);
  p.tick(); assert.equal(p.panel.hidden, true);
  p.document.emit('pointerdown', contact); p.window.emit('blur');
  assert.equal(p.panel.attributes['data-display-gesture'], undefined);
  p.window.emit('focus'); p.tick(); assert.equal(p.panel.hidden, true);
});

test('pointer focus does not pin controls, while keyboard focus, editing and settings do', () => {
  const p = fixture(); p.controller.update({ critical: false });
  p.document.emit('pointerdown', { target: p.grip });
  p.grip.focus(); p.panel.emit('focusin'); p.tick(); assert.equal(p.panel.hidden, true, 'a pointer-clicked button may auto-hide');
  p.document.activeElement = null;
  p.reveal.emit('focus'); assert.equal(p.panel.hidden, false);
  assert.equal(p.document.activeElement, p.grip);
  p.tick(); assert.equal(p.panel.hidden, false, 'Tab reveal preserves focused keyboard controls');
  p.document.emit('pointerdown', { target: p.grip });
  p.grip.matches = () => true; p.tick(); assert.equal(p.panel.hidden, false, 'text editing is never hidden');
  p.grip.matches = () => false;
  p.settings.open = true; p.settings.emit('toggle'); p.tick(); assert.equal(p.panel.hidden, false);
  p.settings.open = false; p.settings.emit('toggle'); p.tick(); assert.equal(p.panel.hidden, true);
});

test('idle reveal has no visible or pointer hit area and display gestures cannot hit revealed chrome', async () => {
  const { readFile } = await import('node:fs/promises');
  const css = await readFile(new URL('../viewer.css', import.meta.url), 'utf8');
  const rule = css.match(/#controls-reveal\s*\{([^}]+)\}/)[1];
  assert.match(rule, /clip-path:\s*inset\(50%\)/);
  assert.match(rule, /pointer-events:\s*none/);
  assert.match(css, /#embedded-controls\[data-display-gesture\] \*\s*\{\s*pointer-events:\s*none/);
  const source = await readFile(new URL('../controls.mjs', import.meta.url), 'utf8');
  assert.match(source, /capture: true, passive: true/);
});

test('assistive reveal activation without a focus event clears stale pointer modality', () => {
  const p = fixture(); p.controller.update({ critical: false });
  p.document.emit('pointerdown', { target: p.grip });
  p.tick(); assert.equal(p.panel.hidden, true);
  p.reveal.emit('click');
  assert.equal(p.document.activeElement, p.grip);
  p.panel.emit('focusin'); p.tick();
  assert.equal(p.panel.hidden, false, 'assistive activation preserves control focus even without a preceding focus event');
});
