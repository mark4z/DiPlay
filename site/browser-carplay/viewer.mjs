import { followBrowserResolution } from './resolution.mjs?v=browser-resolution-v1';
import { floatingControls } from './controls.mjs?v=floating-controls-v1';
import { Contacts, EMBEDDED_VIEWER_ORIGIN, EMBEDDED_VIEWER_ENDPOINT, fitRect, mapPointer } from './core.mjs?v=embedded-https-v1';
import { BrowserSession } from './session.mjs?v=embedded-https-v1';
import { BrowserAudioPlayer } from './audio.mjs?v=webrtc-audio-v1';
import { milestoneText, transportCaption } from './diagnostics.mjs?v=embedded-https-v1';

const byId = id => document.getElementById(id);
const form = byId('connection');
const shell = byId('viewer-shell');
const enter = byId('enter');
const retry = byId('retry');
const stop = byId('stop');
const entryStatus = byId('entry-status');
let entryGeneration = 0;
let fullscreenPending = false;
const ip = byId('ip');
const port = byId('port');
const parked = byId('parked');
const touch = byId('touch');
const touchStatus = byId('touch-status');
const audioButton = byId('audio');
const audioTestButton = byId('audio-test');
const audioStatus = byId('audio-status');
const connect = byId('connect');
const disconnect = byId('disconnect');
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
let audioState = { enabled: false, ready: false, pending: false };
let audioPlayer = null;
let displayControls = null;
let resolutionFollow = null;

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
  byId('settings-content').append(...['touch-control', 'audio-toolbar', 'setup', 'diagnostics', 'display-note', 'requirements', 'legal'].map(byId));
  byId('placeholder-title').textContent = 'DiPlay';
  byId('placeholder-note').textContent = 'Connecting to your Android display…';
  byId('parking-explanation').textContent = 'Use only while safely parked. DiPlay’s Android parked-use guard must be enabled; this page does not detect vehicle speed.';
  byId('local-approval-help').textContent = 'No IP address is needed. DiPlay’s configured approval mode applies. Manual approval requires Accept on Android within 30 seconds.';
  byId('local-endpoint-value').textContent = EMBEDDED_VIEWER_ENDPOINT;
  byId('connection-instructions').textContent = 'This viewer connects automatically to this DiPlay device once when opened. In manual approval mode, tap Accept in DiPlay on Android. Stop or reconnect from the display controls.';
  byId('transport-warning').textContent = 'HTTPS and WSS protect page delivery, video, audio signaling, and touch controls with TLS. WebRTC audio media uses DTLS-SRTP. Keep both devices on your trusted private LAN. DiPlay controls connection approval. Do not bypass certificate warnings.';
  byId('browser-requirements').textContent = 'The built-in HTTPS viewer needs WebCodecs video decoding and a valid TLS connection. No Chrome 147 Local Network Access exemption is needed for the same-origin WSS link. In-car browser and H.265 support still depend on the browser and device. Install the latest DiPlay APK and reload this viewer together; both must support Android approval (protocol v2).';
  byId('connection-troubleshooting').textContent = 'If a connection is blocked, check that DiPlay is enabled, both devices share a trusted private LAN, and the built-in HTTPS page loads with a valid certificate. Do not disable browser security, ignore certificate warnings, or expose the bridge to the internet.';
  byId('lan-diagnostics').hidden = true;
}
if (embeddedViewer) {
  displayControls = floatingControls({ panel: byId('embedded-controls'), grip: byId('controls-grip'),
    reveal: byId('controls-reveal'), settings: byId('viewer-settings'), safeArea: byId('controls-safe-area'), document, window });
}
// Restored form state must never count as a fresh safety/control choice.
parked.checked = touch.checked = false;

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
  connectionAttempt.textContent = attempt ? `Attempt ${attempt}. Times are since Connect; latest attempt only.` : 'No connection attempted.';
  // Fixed stage labels and numeric times/codes only. Never render network text.
  const items = events.map(event => {
    const item = document.createElement('li');
    item.textContent = milestoneText(event);
    return item;
  });
  connectionTimeline.replaceChildren(...items);
}
showDiagnostics({ attempt: 0, transport: null, events: [] });
const session = new BrowserSession({ WebSocket, VideoDecoder: window.VideoDecoder,
  EncodedVideoChunk: window.EncodedVideoChunk, onState: setState, onFrame: queueFrame, onTouchOwnership: setTouchState,
  onDiagnostics: showDiagnostics,
  onBrowserResolution: message => resolutionFollow?.acknowledged(message),
  onAudioMessage: message => audioPlayer?.handleMessage(message),
  onAudioPacket: packet => audioPlayer?.handlePacket(packet),
  onAudioReset: () => audioPlayer?.reset() });
audioPlayer = new BrowserAudioPlayer({
  sendMode: (enabled, requestId, source) => session.setAudioEnabled(enabled, requestId, source),
  sendSignal: message => session.sendAudioSignal(message),
  onState: state => {
    audioState = state;
    audioStatus.textContent = state.message;
    byId('compact-audio-error').textContent = state.error ? state.message : '';
    byId('compact-audio-error').hidden = !state.error;
    updateControls();
  },
});

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
  resolutionFollow = followBrowserResolution({ measure: () => viewport.getBoundingClientRect(),
    send: message => session.setBrowserResolution(message),
    onStatus: message => { byId('resolution-status').textContent = message; } });
  byId('resolution-follow').addEventListener('change', () => {
    if (!resolutionFollow.enable(byId('resolution-follow').checked)) {
      byId('resolution-follow').checked = !byId('resolution-follow').checked;
      byId('resolution-status').textContent = 'Wait for Android to confirm before changing this option.';
    }
  });
  const changed = () => resolutionFollow.changed();
  if (window.ResizeObserver) new window.ResizeObserver(changed).observe(viewport);
  window.addEventListener('resize', changed);
  document.addEventListener('fullscreenchange', changed);
}

// Read-only, bounded operational counters for parked manual validation.
export const getAudioDiagnostics = () => audioPlayer.getDiagnostics();

function updateControls() {
  displayControls?.update({ critical: !live || Boolean(blocked) || Boolean(audioState.error) || !entryStatus.hidden });
  const active = !session.closed;
  resolutionFollow?.connected(session.authenticated && active && document.visibilityState === 'visible');
  if (embeddedViewer) byId('resolution-follow').disabled = !session.authenticated || !active;
  connect.disabled = Boolean(blocked) || active || !parkedUseAllowed();
  disconnect.disabled = stop.disabled = !active;
  retry.hidden = active || Boolean(blocked);
  retry.disabled = connect.disabled;
  enter.disabled = Boolean(blocked) || !session.authenticated || !active || fullscreenPending || audioState.pending;
  const fullscreen = document.fullscreenElement === shell;
  enter.hidden = fullscreen && audioState.enabled;
  enter.textContent = fullscreen ? 'Play audio here' : audioState.enabled ? 'Enter fullscreen' : 'Enter fullscreen + audio';
  ip.disabled = port.disabled = embeddedViewer || active;
  audioButton.disabled = !session.authenticated || !active || !parkedUseAllowed();
  audioTestButton.disabled = audioButton.disabled || audioState.pending || audioState.enabled;
  audioButton.textContent = audioState.pending ? 'Cancel audio start'
    : (audioState.enabled ? 'Return audio to Android' : 'Play audio here');
  touch.disabled = (!live && !session.touchRequested) || !parkedUseAllowed() || active === false || !window.PointerEvent;
}

function setState(state, text) {
  live = state === 'live';
  if (!live) {
    releaseContacts();
    // The session releases native ownership and invalidates ACKs. Keep the
    // explicit checkbox intent while video recovers in this approved socket.
    touch.checked = session.touchRequested;
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
  touch.checked = requested;
  canvas.classList.toggle('touch-enabled', enabled && live && parkedUseAllowed());
  touchStatus.textContent = pending
    ? (requested ? 'Waiting for Android to enable touch…' : 'Releasing touch control…')
    : (enabled ? 'Touch control active' : (requested ? 'Touch paused while video recovers…' : 'Touch control off'));
  byId('compact-touch-state').textContent = pending ? 'Touch pending' : enabled ? 'Touch on' : requested ? 'Touch paused' : 'Touch off';
  updateControls();
}

function clearPicture() {
  if (drawRequest !== null) cancelAnimationFrame(drawRequest);
  drawRequest = null;
  if (pendingFrame) pendingFrame.close();
  pendingFrame = null;
  videoWidth = videoHeight = 0;
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
  if (!session.touchOwned || !touch.checked || !live || !parkedUseAllowed() || (event.pointerType === 'mouse' && event.button !== 0)) return;
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
  if (event.pointerType === 'mouse' && event.buttons === 0) { finishPointer(event); return; }
  if (contacts.move(event.pointerId, point(event, true)) && moveRequest === null) moveRequest = requestAnimationFrame(flushContacts);
});

function finishPointer(event) {
  if (!contacts.up(event.pointerId)) return;
  event.preventDefault();
  // Up/cancel sends the surviving contacts immediately, including an empty final
  // snapshot. Native pointer IDs never become reordered CarPlay contact slots.
  flushContacts();
  if (canvas.hasPointerCapture?.(event.pointerId)) canvas.releasePointerCapture(event.pointerId);
}
for (const name of ['pointerup', 'pointercancel', 'lostpointercapture']) canvas.addEventListener(name, finishPointer);
canvas.addEventListener('contextmenu', event => { if (session.touchOwned) event.preventDefault(); });

audioButton.addEventListener('click', () => {
  if (!session.authenticated || session.closed || !parkedUseAllowed() || document.visibilityState !== 'visible') return;
  if (audioState.enabled || audioState.pending) audioPlayer.disable();
  else void audioPlayer.enableFromGesture();
});

audioTestButton.addEventListener('click', () => {
  if (!session.authenticated || session.closed || !parkedUseAllowed() || document.visibilityState !== 'visible') return;
  void audioPlayer.enableFromGesture({ test: true });
});

touch.addEventListener('change', () => {
  if (!parkedUseAllowed() || (!live && touch.checked)) touch.checked = false;
  releaseContacts();
  if (!session.setTouchOwnership(touch.checked)) touch.checked = false;
  canvas.classList.toggle('touch-enabled', session.touchOwned);
});
parked.addEventListener('change', () => {
  if (!embeddedViewer && !parked.checked) { audioPlayer.disable(); releaseContacts(); session.close('Disconnected. Park safely before connecting again.'); }
  updateControls();
});
function stopSession() { audioPlayer.disable(); releaseContacts(); session.close(); }
disconnect.addEventListener('click', stopSession);
stop.addEventListener('click', stopSession);
function connectSession() {
  if (blocked || !parkedUseAllowed() || !session.closed || document.visibilityState !== 'visible') return;
  try {
    touch.checked = false;
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
retry.addEventListener('click', connectSession);

enter.addEventListener('click', () => {
  if (!embeddedViewer || blocked || !session.authenticated || session.closed || fullscreenPending || document.visibilityState !== 'visible') return;
  const generation = ++entryGeneration;
  entryStatus.hidden = true;
  // Both browser APIs are invoked synchronously inside this real user gesture.
  // Calling audio first avoids consuming activation on fullscreen before play().
  if (!audioState.enabled && !audioState.pending) void audioPlayer.enableFromGesture();
  if (document.fullscreenElement === shell) return;
  const failed = () => {
    if (generation !== entryGeneration || session.closed) return;
    fullscreenPending = false;
    entryStatus.textContent = 'Browser fullscreen is unavailable. The display still fills this page; you can retry Enter or use the browser’s fullscreen control.';
    entryStatus.hidden = false;
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
});
document.addEventListener('fullscreenchange', () => { releaseContacts(); updateControls(); });
byId('viewer-settings').addEventListener('toggle', releaseContacts);

function leavePage() {
  audioPlayer.disable();
  releaseContacts();
  session.close('Disconnected because this page is no longer visible. Click Connect to request approval again.');
  parked.checked = touch.checked = false;
  updateControls();
}
document.addEventListener('visibilitychange', () => { if (document.visibilityState !== 'visible') leavePage(); });
window.addEventListener('pagehide', leavePage);
window.addEventListener('blur', releaseContacts);
window.addEventListener('pageshow', event => { if (event.persisted) leavePage(); });
// Changing the picture bounds mid-gesture must not leave pressed contacts behind.
if (window.ResizeObserver) new ResizeObserver(releaseContacts).observe(viewport);
else window.addEventListener('resize', releaseContacts);

status.textContent = blocked || (embeddedViewer
  ? 'Ready to connect securely to this DiPlay device.'
  : 'Ready. Confirm you are parked, then enter the bridge IP and port to request Android approval.');
indicator.dataset.state = byId('display-toolbar').dataset.state = blocked ? 'error' : 'closed';
if (blocked && embeddedViewer) byId('placeholder-note').textContent = blocked;
updateControls();
// Exactly one attempt for a freshly opened, visible built-in page. Failure,
// timeout, Stop, tab return, and BFCache restoration never schedule a retry.
if (embeddedViewer) connectSession();
