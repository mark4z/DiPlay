import test from 'node:test';
import assert from 'node:assert/strict';

const canonicalOrigin = 'https://tesla.mark4z.asia:9999';
const scenarios = [
  { name: 'insecure page', protocol: 'http:', origin: 'http://tesla.mark4z.asia:9999', message: /over HTTPS/ },
  { name: 'untrusted secure context', secure: false, message: /over HTTPS/ },
  { name: 'embedded frame', framed: true, message: /own browser tab/ },
  { name: 'missing WebCodecs decoder', decoder: false, message: /WebCodecs/ },
  { name: 'missing encoded chunk support', chunk: false, message: /WebCodecs/ },
  { name: 'missing canvas support', canvas: false, message: /WebCodecs/ },
  { name: 'external old Chromium', origin: 'https://mark4z.github.io', userAgent: 'Chrome/130.0', message: /Chrome 147/ },
  { name: 'external WebView', origin: 'https://mark4z.github.io', userAgent: '(Linux; wv) Chrome/154.0', message: /Chrome 147/ },
  { name: 'lookalike hostname', origin: 'https://tesla.mark4z.asia.untrusted.invalid:9999', userAgent: 'Chrome/130.0', message: /Chrome 147/ },
  { name: 'wrong TLS port', origin: 'https://tesla.mark4z.asia:9998', userAgent: 'Chrome/130.0', message: /Chrome 147/ },
];

for (const [index, scenario] of scenarios.entries()) {
  test(`${scenario.name} stays blocked even after parked confirmation and manual submit`, async () => {
    class Element {
      constructor() {
        this.style = {}; this.listeners = new Map(); this.dataset = {}; this.checked = false; this.value = '';
        this.classList = { remove() {}, toggle() {} };
      }
      addEventListener(name, callback) { this.listeners.set(name, callback); }
      dispatch(name) { this.listeners.get(name)?.({ preventDefault() {} }); }
      contains() { return false; }
      setAttribute() {}
      getBoundingClientRect() { return { left: 12, top: 12, right: 1000, bottom: 700, width: 500, height: 60 }; }
      getContext() { return scenario.canvas === false ? null : {}; }
      replaceChildren() {}
      append() {}
    }
    const elements = new Map();
    const element = id => {
      if (!elements.has(id)) elements.set(id, new Element());
      return elements.get(id);
    };
    let sockets = 0;
    const decoder = scenario.decoder === false ? undefined : class {};
    const chunk = scenario.chunk === false ? undefined : class {};
    const window = { VideoDecoder: decoder, EncodedVideoChunk: chunk, addEventListener() {} };
    window.self = window;
    window.top = scenario.framed ? {} : window;
    Object.assign(globalThis, {
      window, document: { getElementById: element, visibilityState: 'visible', addEventListener() {} },
      location: { protocol: scenario.protocol || 'https:', origin: scenario.origin || canonicalOrigin },
      isSecureContext: scenario.secure !== false,
      VideoDecoder: decoder, EncodedVideoChunk: chunk,
      WebSocket: class { constructor() { sockets++; } },
    });
    Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { userAgent: scenario.userAgent || 'Chrome/154.0' } });
    await import(`../viewer.mjs?compatibility-${index}`);
    assert.match(element('status').textContent, scenario.message);
    element('ip').value = '192.168.1.20';
    element('port').value = '8765';
    element('parked').checked = true;
    element('parked').dispatch('change');
    element('connection').dispatch('submit');
    assert.equal(sockets, 0);
    if (scenario.origin && scenario.origin !== canonicalOrigin) assert.equal(element('manual-endpoint').hidden, false);
  });
}
