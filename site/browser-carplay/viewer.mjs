import { Contacts, EMBEDDED_VIEWER_ORIGIN, EMBEDDED_VIEWER_ENDPOINT, fitRect, mapPointer } from './core.mjs?v=embedded-https-v1';
import { BrowserSession } from './session.mjs?v=embedded-https-v1';
import { BrowserAudioPlayer } from './audio.mjs?v=webrtc-audio-v1';
import { milestoneText, transportCaption } from './diagnostics.mjs?v=embedded-https-v1';

const byId = id => document.getElementById(id);
const form = byId('connection');
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

// The browser's actual origin is the only selector. URL parameters, fragments,
// persisted input and server-provided config can never override the destination.
const embeddedViewer = location.protocol === 'https:' && location.origin === EMBEDDED_VIEWER_ORIGIN;
byId('origin').textContent = location.protocol === 'https:' ? location.origin : 'an HTTPS website origin';
byId('manual-endpoint').hidden = embeddedViewer;
byId('local-endpoint').hidden = !embeddedViewer;
ip.required = port.required = !embeddedViewer;
if (embeddedViewer) {
  ip.value = port.value = '';
  byId('local-endpoint-value').textContent = EMBEDDED_VIEWER_ENDPOINT;
  byId('connection-instructions').textContent = 'This viewer is served by DiPlay on your Android device. Confirm parked use, click Connect, then tap Accept in DiPlay on Android. No IP address entry is needed.';
  byId('transport-warning').textContent = 'HTTPS and WSS protect page delivery, video, audio signaling, and touch controls with TLS. WebRTC audio media uses DTLS-SRTP. Keep both devices on your trusted private LAN. Android approval is still required. Do not bypass certificate warnings.';
  byId('browser-requirements').textContent = 'The built-in HTTPS viewer needs WebCodecs video decoding and a valid TLS connection. No Chrome 147 Local Network Access exemption is needed for the same-origin WSS link. In-car browser and H.265 support still depend on the browser and device. Install the latest DiPlay APK and reload this viewer together; both must support Android approval (protocol v2).';
  byId('connection-troubleshooting').textContent = 'If a connection is blocked, check that DiPlay is enabled, both devices share a trusted private LAN, and the built-in HTTPS page loads with a valid certificate. Do not disable browser security, ignore certificate warnings, or expose the bridge to the internet.';
  byId('lan-diagnostics').hidden = true;
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
  onAudioMessage: message => audioPlayer?.handleMessage(message),
  onAudioPacket: packet => audioPlayer?.handlePacket(packet),
  onAudioReset: () => audioPlayer?.reset() });
audioPlayer = new BrowserAudioPlayer({
  sendMode: (enabled, requestId, source) => session.setAudioEnabled(enabled, requestId, source),
  sendSignal: message => session.sendAudioSignal(message),
  onState: state => {
    audioState = state;
    audioStatus.textContent = state.message;
    updateControls();
  },
});

// Read-only, bounded operational counters for parked manual validation.
export const getAudioDiagnostics = () => audioPlayer.getDiagnostics();

function updateControls() {
  const active = !session.closed;
  connect.disabled = Boolean(blocked) || active || !parked.checked;
  disconnect.disabled = !active;
  ip.disabled = port.disabled = embeddedViewer || active;
  audioButton.disabled = !session.authenticated || !active || !parked.checked;
  audioTestButton.disabled = audioButton.disabled || audioState.pending || audioState.enabled;
  audioButton.textContent = audioState.pending ? 'Cancel audio start'
    : (audioState.enabled ? 'Return audio to Android' : 'Play audio here');
  touch.disabled = (!live && !session.touchRequested) || !parked.checked || active === false || !window.PointerEvent;
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
  updateControls();
}

function setTouchState({ enabled, requested, pending }) {
  if (!enabled) releaseContacts();
  touch.checked = requested;
  canvas.classList.toggle('touch-enabled', enabled && live && parked.checked);
  touchStatus.textContent = pending
    ? (requested ? 'Waiting for Android to enable touch…' : 'Releasing touch control…')
    : (enabled ? 'Touch control active' : (requested ? 'Touch paused while video recovers…' : 'Touch control off'));
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
  if (!session.touchOwned || !touch.checked || !live || !parked.checked || (event.pointerType === 'mouse' && event.button !== 0)) return;
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
  if (!session.authenticated || session.closed || !parked.checked || document.visibilityState !== 'visible') return;
  if (audioState.enabled || audioState.pending) audioPlayer.disable();
  else void audioPlayer.enableFromGesture();
});

audioTestButton.addEventListener('click', () => {
  if (!session.authenticated || session.closed || !parked.checked || document.visibilityState !== 'visible') return;
  void audioPlayer.enableFromGesture({ test: true });
});

touch.addEventListener('change', () => {
  if (!parked.checked || (!live && touch.checked)) touch.checked = false;
  releaseContacts();
  if (!session.setTouchOwnership(touch.checked)) touch.checked = false;
  canvas.classList.toggle('touch-enabled', session.touchOwned);
});
parked.addEventListener('change', () => {
  if (!parked.checked) { audioPlayer.disable(); releaseContacts(); session.close('Disconnected. Park safely before connecting again.'); }
  updateControls();
});
disconnect.addEventListener('click', () => { audioPlayer.disable(); releaseContacts(); session.close(); });
form.addEventListener('submit', event => {
  event.preventDefault();
  if (blocked || !parked.checked || !session.closed || document.visibilityState !== 'visible') return;
  try {
    touch.checked = false;
    if (embeddedViewer) session.connect(undefined, undefined, location.origin);
    else session.connect(ip.value, port.value);
  } catch (error) {
    status.textContent = error.message;
    indicator.dataset.state = 'error';
    updateControls();
  }
});

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
  ? 'Ready. Confirm you are parked, then click Connect to request Android approval over TLS.'
  : 'Ready. Confirm you are parked, then enter the bridge IP and port to request Android approval.');
indicator.dataset.state = blocked ? 'error' : 'closed';
updateControls();
