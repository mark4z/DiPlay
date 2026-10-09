import { followBrowserResolution, observeRenderPixels } from './resolution.mjs?v=fullscreen-state-v1';
import { floatingControls } from './controls.mjs?v=floating-controls-v1';
import { Contacts, EMBEDDED_VIEWER_ORIGIN, EMBEDDED_VIEWER_ENDPOINT, fitRect, mapPointer } from './core.mjs?v=embedded-https-v1';
import { BrowserSession, AutoReconnect } from './session.mjs?v=automatic-viewer-v1';
import { milestoneText, transportCaption } from './diagnostics.mjs?v=embedded-https-v1';

const byId = id => document.getElementById(id);
const form = byId('connection');
const shell = byId('viewer-shell');
const enter = byId('enter');
const entryStatus = byId('entry-status');
let entryGeneration = 0;
let fullscreenPending = false;
let automaticFullscreenAttempted = false;
const ip = byId('ip');
const port = byId('port');
const parked = byId('parked');
const touchStatus = byId('touch-status');
const canvas = byId('video');
const viewport = byId('viewport');
const placeholder = byId('placeholder');
const status = byId('status');
const indicator = byId('indicator');
const connectionTimeline = byId('connection-timeline');
const connectionAttempt = byId('connection-attempt');
const connectionTransport = byId('connection-transport');
const context = canvas.getContext('2d', { alpha: false, desynchronized: true });
const contacts = new Contacts();
let pendingFrame = null;
let drawRequest = null;
let moveRequest = null;
let videoWidth = 0;
let videoHeight = 0;
let live = false;
let displayControls = null;
let resolutionFollow = null;
let reconnect = null;

// The browser's actual origin is the only selector. URL parameters, fragments,
// persisted input and server-provided config can never override the destination.
const embeddedViewer = location.protocol === 'https:' && location.origin === EMBEDDED_VIEWER_ORIGIN;
// The Android bridge's explicitly enabled parked-use guard remains authoritative
// in embedded mode. Never synthesize a checked browser consent box.
const parkedUseAllowed = () => embeddedViewer || parked.checked;
shell.classList.toggle('embedded-viewer', embeddedViewer);
byId('origin').textContent = location.protocol === 'https:' ? location.origin : 'an HTTPS website origin';
byId('manual-endpoint').hidden = embeddedViewer;
byId('local-endpoint').hidden = !embeddedViewer;
ip.required = port.required = !embeddedViewer;
if (embeddedViewer) {
  ip.value = port.value = '';
  byId('embedded-controls').hidden = false;
  byId('page-header').hidden = byId('intro').hidden = true;
  byId('parked-control').hidden = true;
  parked.required = false;
  byId('settings-content').append(...['touch-control', 'setup', 'diagnostics', 'display-note', 'requirements', 'legal'].map(byId));
  byId('placeholder-title').textContent = 'DiPlay';
  byId('placeholder-note').textContent = 'Connecting to your Android display…';
  byId('parking-explanation').textContent = 'Use only while safely parked. DiPlay’s Android parked-use guard must be enabled; this page does not detect vehicle speed.';
  byId('local-approval-help').textContent = 'No IP address is needed. DiPlay’s configured approval mode applies. Manual approval requires Accept on Android within 30 seconds.';
  byId('local-endpoint-value').textContent = EMBEDDED_VIEWER_ENDPOINT;
  byId('connection-instructions').textContent = 'This viewer connects automatically and retries every five seconds while disconnected and visible. In manual approval mode, tap Accept in DiPlay on Android. A newly approved browser replaces the previous viewer; reload a replaced page to take over again.';
  byId('transport-warning').textContent = 'HTTPS and WSS protect page delivery, video, and touch controls with TLS. Keep both devices on your trusted private LAN. DiPlay controls connection approval. Do not bypass certificate warnings.';
  byId('browser-requirements').textContent = 'The built-in HTTPS viewer needs WebCodecs video decoding and a valid TLS connection. No Chrome 147 Local Network Access exemption is needed for the same-origin WSS link. In-car browser and H.265 support still depend on the browser and device. Install the latest DiPlay APK and reload this viewer together; both must support Android approval (protocol v2).';
  byId('connection-troubleshooting').textContent = 'If a connection is blocked, check that DiPlay is enabled, both devices share a trusted private LAN, and the built-in HTTPS page loads with a valid certificate. Do not disable browser security, ignore certificate warnings, or expose the bridge to the internet.';
  byId('lan-diagnostics').hidden = true;
}
if (embeddedViewer) {
  displayControls = floatingControls({ panel: byId('embedded-controls'), grip: byId('controls-grip'),
    reveal: byId('controls-reveal'), settings: byId('viewer-settings'), safeArea: byId('controls-safe-area'), document, window });
}
// Restored form state must never count as fresh parked-use consent.
parked.checked = false;

function compatibilityError() {
  if (!isSecureContext || location.protocol !== 'https:') return 'Open this viewer over HTTPS. Insecure pages cannot connect.';
  if (window.top !== window.self) return 'Open this viewer directly in its own browser tab.';
  if (typeof VideoDecoder !== 'function' || typeof EncodedVideoChunk !== 'function' || !context) return 'This browser does not provide the required WebCodecs video decoder and canvas.';
  // LNA has no reliable synchronous feature probe. Known old Chromium/WebView
  // builds are blocked here; the browser enforces its actual permission policy.
  const chromium = /(?:Chrome|Chromium)\/(\d+)/.exec(navigator.userAgent);
  if (!embeddedViewer && (!chromium || Number(chromium[1]) < 147 || /; wv\)/.test(navigator.userAgent))) return 'Use Chrome 147 or later with WebCodecs and Local Network Access. This browser is unsupported.';
  return null;
}

const blocked = compatibilityError();
function showDiagnostics({ attempt, transport, events }) {
  connectionTransport.textContent = transportCaption({ protocol: location.protocol, secureContext: isSecureContext, transport });
  connectionAttempt.textContent = attempt ? `Attempt ${attempt}. Times are since connection started; latest attempt only.` : 'No connection attempted.';
  // Fixed stage labels and numeric times/codes only. Never render network text.
  const items = events.map(event => {
    const item = document.createElement('li');
    item.textContent = milestoneText(event);
    return item;
  });
  connectionTimeline.replaceChildren(...items);
}
showDiagnostics({ attempt: 0, transport: null, events: [] });
const session = new BrowserSession({ autoTouch: Boolean(window.PointerEvent), WebSocket, VideoDecoder: window.VideoDecoder,
  EncodedVideoChunk: window.EncodedVideoChunk, onState: setState, onFrame: queueFrame, onTouchOwnership: setTouchState,
  onDiagnostics: showDiagnostics,
  onBrowserResolution: message => resolutionFollow?.acknowledged(message) });

if (embeddedViewer) {
  const closeSettings = () => {
    byId('viewer-settings').open = false;
    byId('controls-grip').focus({ preventScroll: true });
  };
  byId('settings-close').addEventListener('click', closeSettings);
  byId('settings-content').addEventListener('keydown', event => {
    if (event.key === 'Escape') { event.preventDefault(); closeSettings(); }
  });
  byId('resolution-settings').hidden = false;
  byId('resolution-follow').checked = true;
  const changed = () => resolutionFollow?.changed();
  const measure = observeRenderPixels(viewport, changed);
  resolutionFollow = followBrowserResolution({ measure,
    send: message => session.setBrowserResolution(message),
    onStatus: message => { byId('resolution-status').textContent = message; renderResolutionControl(); },
    onTarget: size => { byId('resolution-target').textContent = size ? `${size.width} × ${size.height} px` : '等待 Android 确认'; } });
  byId('resolution-follow').addEventListener('change', () => {
    if (!resolutionFollow.enable(byId('resolution-follow').checked)) {
      byId('resolution-status').textContent = 'Wait for Android to confirm before changing this option.';
    }
    renderResolutionControl();
  });
  window.addEventListener('resize', changed);
  document.addEventListener('fullscreenchange', changed);
}

function renderResolutionControl() {
  if (!resolutionFollow) return;
  byId('resolution-follow').checked = resolutionFollow.enabled;
  byId('resolution-follow-state').textContent = resolutionFollow.enabled ? 'On' : 'Off';
}

function updateControls() {
  renderResolutionControl();
  displayControls?.update({ critical: !live || Boolean(blocked) || !entryStatus.hidden });
  const active = !session.closed;
  resolutionFollow?.connected(session.authenticated && active && document.visibilityState === 'visible');
  if (embeddedViewer) byId('resolution-follow').disabled = !session.authenticated || !active;
  enter.disabled = Boolean(blocked) || !session.authenticated || !active || fullscreenPending;
  const fullscreen = document.fullscreenElement === shell;
  enter.hidden = fullscreen;
  enter.textContent = 'Enter fullscreen';
  ip.disabled = port.disabled = embeddedViewer || active;
  reconnect?.update();
  if (!automaticFullscreenAttempted && embeddedViewer && !blocked && session.authenticated && active && document.visibilityState === 'visible') {
    automaticFullscreenAttempted = true;
    requestViewerFullscreen(true);
  }
}

function setState(state, text) {
  live = state === 'live';
  if (!live) {
    releaseContacts();
    canvas.classList.remove('touch-enabled');
    clearPicture();
  }
  status.textContent = text;
  indicator.dataset.state = state;
  byId('display-toolbar').dataset.state = state;
  if (embeddedViewer) byId('placeholder-note').textContent = text;
  if (session.closed) {
    ++entryGeneration;
    fullscreenPending = false;
    entryStatus.hidden = true;
  }
  updateControls();
}

function setTouchState({ enabled, requested, pending }) {
  if (!enabled) releaseContacts();
  canvas.classList.toggle('touch-enabled', enabled && live && parkedUseAllowed());
  touchStatus.textContent = !window.PointerEvent ? 'This browser does not support touch input.' : pending
    ? (requested ? 'Waiting for Android to enable touch…' : 'Releasing touch control…')
    : (enabled ? 'Touch control active' : (requested ? 'Touch paused while video recovers…' : 'Waiting for live video…'));
  byId('compact-touch-state').textContent = pending ? 'Touch pending' : enabled ? 'Touch on' : requested ? 'Touch paused' : 'Touch waiting';
  updateControls();
}

function clearPicture() {
  if (drawRequest !== null) cancelAnimationFrame(drawRequest);
  drawRequest = null;
  if (pendingFrame) pendingFrame.close();
  pendingFrame = null;
  videoWidth = videoHeight = 0;
  byId('resolution-actual').textContent = '等待视频';
  context?.clearRect(0, 0, canvas.width, canvas.height);
  placeholder.hidden = false;
}

function queueFrame(frame) {
  if (document.visibilityState !== 'visible' || session.closed) { frame.close(); return; }
  if (pendingFrame) pendingFrame.close();
  pendingFrame = frame;
  if (drawRequest === null) drawRequest = requestAnimationFrame(drawFrame);
}

function drawFrame() {
  drawRequest = null;
  const frame = pendingFrame;
  pendingFrame = null;
  if (!frame) return;
  try {
    if (!live || document.visibilityState !== 'visible') return;
    if (videoWidth !== frame.displayWidth || videoHeight !== frame.displayHeight) releaseContacts();
    videoWidth = frame.displayWidth;
    videoHeight = frame.displayHeight;
    const bounds = canvas.getBoundingClientRect();
    // Cap backing pixels on high-DPI devices without changing hit-test geometry.
    const ratio = Math.min(devicePixelRatio || 1, 2);
    const width = Math.max(1, Math.round(bounds.width * ratio));
    const height = Math.max(1, Math.round(bounds.height * ratio));
    if (canvas.width !== width || canvas.height !== height) { canvas.width = width; canvas.height = height; }
    const rect = fitRect(width, height, videoWidth, videoHeight);
    if (!rect) return;
    context.fillStyle = '#000';
    context.fillRect(0, 0, width, height);
    context.drawImage(frame, rect.x, rect.y, rect.width, rect.height);
    placeholder.hidden = true;
    const actualSize = `${frame.displayWidth} × ${frame.displayHeight} px`;
    if (byId('resolution-actual').textContent !== actualSize) byId('resolution-actual').textContent = actualSize;
  } catch {
    session.close('This browser could not display the video.', true);
  } finally {
    frame.close();
  }
}

function flushContacts() {
  if (moveRequest !== null) cancelAnimationFrame(moveRequest);
  moveRequest = null;
  session.sendContacts(contacts.snapshot());
}

function releaseContacts() {
  if (moveRequest !== null) cancelAnimationFrame(moveRequest);
  moveRequest = null;
  const captured = contacts.pointerIds;
  const hadContacts = contacts.size > 0;
  contacts.clear();
  if (hadContacts) session.sendContacts([]);
  for (const pointerId of captured) {
    if (canvas.hasPointerCapture?.(pointerId)) canvas.releasePointerCapture(pointerId);
  }
}

function point(event, clamp = false) {
  const bounds = canvas.getBoundingClientRect();
  // Use the actual drawn bitmap, including while CSS resizes it between frames.
  // Re-fitting directly to the new CSS bounds would misidentify the black bars.
  return mapPointer((event.clientX - bounds.left) * canvas.width / bounds.width,
    (event.clientY - bounds.top) * canvas.height / bounds.height,
    { left: 0, top: 0, width: canvas.width, height: canvas.height }, videoWidth, videoHeight, clamp);
}

canvas.addEventListener('pointerdown', event => {
  if (!session.touchOwned || !live || !parkedUseAllowed() || (event.pointerType === 'mouse' && event.button !== 0)) return;
  const coordinate = point(event);
  if (!coordinate || !contacts.down(event.pointerId, coordinate)) return;
  event.preventDefault();
  try { canvas.setPointerCapture(event.pointerId); }
  catch { contacts.up(event.pointerId); return; }
  flushContacts();
});

canvas.addEventListener('pointermove', event => {
  if (!contacts.has(event.pointerId)) return;
  event.preventDefault();
  if (event.pointerType === 'mouse' && event.buttons === 0) { finishPointer(event, true); return; }
  if (contacts.move(event.pointerId, point(event, true)) && moveRequest === null) moveRequest = requestAnimationFrame(flushContacts);
});

function finishPointer(event, useReleasePosition = false) {
  if (!contacts.has(event.pointerId)) return;
  event.preventDefault();
  if (useReleasePosition) {
    // UP can arrive before the pending move's animation frame, or contain a
    // newer position itself. Send that pressed state before removing its slot.
    // Cancel/lost capture only release: their coordinates need not be usable.
    const moved = contacts.move(event.pointerId, point(event, true));
    if (moved || moveRequest !== null) flushContacts();
  }
  // A failed flush can close the session and synchronously clear all contacts.
  if (!contacts.up(event.pointerId)) return;
  // Up/cancel sends the surviving contacts immediately, including an empty final
  // snapshot. Native pointer IDs never become reordered CarPlay contact slots.
  flushContacts();
  if (canvas.hasPointerCapture?.(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
}
canvas.addEventListener('pointerup', event => finishPointer(event, true));
for (const name of ['pointercancel', 'lostpointercapture']) canvas.addEventListener(name, event => finishPointer(event));
canvas.addEventListener('contextmenu', event => { if (session.touchOwned) event.preventDefault(); });

parked.addEventListener('change', () => {
  if (!embeddedViewer && !parked.checked) { releaseContacts(); session.close('Disconnected. Park safely before connecting again.'); }
  updateControls();
  reconnect?.update(true);
});
function connectSession() {
  if (blocked || session.retryBlocked || !parkedUseAllowed() || !session.closed || document.visibilityState !== 'visible') return;
  try {
    if (embeddedViewer) session.connect(undefined, undefined, location.origin);
    else session.connect(ip.value, port.value);
  } catch (error) {
    status.textContent = error.message;
    indicator.dataset.state = 'error';
    byId('display-toolbar').dataset.state = 'error';
    updateControls();
  }
}
form.addEventListener('submit', event => { event.preventDefault(); connectSession(); });
for (const input of [ip, port]) input.addEventListener('change', () => reconnect?.update(true));
reconnect = new AutoReconnect({ connect: connectSession,
  eligible: () => !blocked && parkedUseAllowed() && document.visibilityState === 'visible' && (embeddedViewer || Boolean(ip.value && port.value)),
  closed: () => session.closed, blocked: () => session.retryBlocked });

function requestViewerFullscreen(automatic = false) {
  if (!embeddedViewer || blocked || !session.authenticated || session.closed || fullscreenPending || document.visibilityState !== 'visible') return;
  const generation = ++entryGeneration;
  entryStatus.hidden = true;
  // Try once after approval. Browsers may reject without user activation; the
  // manual button retries synchronously in its real click without intercepting touch.
  if (document.fullscreenElement === shell) return;
  const failed = () => {
    if (generation !== entryGeneration || session.closed) return;
    fullscreenPending = false;
    if (!automatic) {
      entryStatus.textContent = 'Browser fullscreen is unavailable. The display still fills this page; you can retry Enter or use the browser’s fullscreen control.';
      entryStatus.hidden = false;
    }
    updateControls();
  };
  if (typeof shell.requestFullscreen !== 'function') { failed(); return; }
  fullscreenPending = true;
  updateControls();
  try {
    Promise.resolve(shell.requestFullscreen()).then(() => {
      if (generation !== entryGeneration || session.closed) return;
      fullscreenPending = false;
      updateControls();
    }, failed);
  } catch { failed(); }
}
enter.addEventListener('click', () => requestViewerFullscreen());
document.addEventListener('fullscreenchange', () => { releaseContacts(); updateControls(); });
byId('viewer-settings').addEventListener('toggle', releaseContacts);

function leavePage() {
  reconnect.suspend();
  releaseContacts();
  session.close('Connection paused while this page is hidden.');
  parked.checked = false;
  updateControls();
}
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState !== 'visible') leavePage();
  else reconnect.resume();
});
window.addEventListener('pagehide', leavePage);
window.addEventListener('blur', releaseContacts);
window.addEventListener('pageshow', () => {
  renderResolutionControl();
  if (document.visibilityState === 'visible') reconnect.resume();
});
// Changing the picture bounds mid-gesture must not leave pressed contacts behind.
if (window.ResizeObserver) new ResizeObserver(releaseContacts).observe(viewport);
else window.addEventListener('resize', releaseContacts);

status.textContent = blocked || (embeddedViewer
  ? 'Ready to connect securely to this DiPlay device.'
  : 'Ready. Confirm you are parked, then enter the bridge IP and port to request Android approval.');
indicator.dataset.state = byId('display-toolbar').dataset.state = blocked ? 'error' : 'closed';
if (blocked && embeddedViewer) byId('placeholder-note').textContent = blocked;
updateControls();
// Connection attempts are serialized and never run in a hidden page.
reconnect.update(true);

