// CSS viewport size is the baseline. Android applies its existing resolution
// percentage and encoder alignment; decoder frame size must never feed back here.
export function browserSize(rect) {
  const width = Math.round(rect.width), height = Math.round(rect.height);
  if (!Number.isSafeInteger(width) || !Number.isSafeInteger(height) || Math.min(width, height) < 320 ||
      Math.max(width, height) > 16384 || width / height > 3 || height / width > 3) return null;
  return { width, height };
}

export function followBrowserResolution({ measure, send, onStatus, setTimer = setTimeout, clearTimer = clearTimeout }) {
  let ready = false, enabled = true, timer = null, deadline = null, pending = null, acknowledgedId = null, last = null, nextId = 0;
  function cancel() { if (timer !== null) clearTimer(timer); timer = null; }
  function settle() {
    cancel();
    if (!ready || !enabled || pending) return;
    timer = setTimer(() => { timer = null; request(true); }, 2000);
  }
  function request(follow) {
    if (!ready || pending) return;
    const size = follow ? browserSize(measure()) : null;
    if (follow && !size) { onStatus('This browser viewport is too small or unusually shaped for automatic resolution.'); return; }
    const key = follow ? `${size.width}x${size.height}` : 'off';
    if (key === last) return;
    const requestId = ++nextId;
    if (!Number.isSafeInteger(requestId)) return;
    acknowledgedId = null;
    pending = { requestId, key };
    last = key;
    if (!send({ requestId, enabled: follow, ...(size || {}) })) {
      pending = null;
      onStatus('Resolution could not be sent. Reconnect the browser to try again.');
      return;
    }
    onStatus('Waiting for Android to confirm the browser resolution…');
    deadline = setTimer(() => {
      deadline = null;
      pending = null;
      onStatus('Android did not confirm the resolution. Reload with the latest APK or reconnect to try again.');
      // Do not repeatedly restart CarPlay when an acknowledgment is missing.
    }, 10000);
  }
  return {
    changed: settle,
    connected(value) {
      if (ready === value) return;
      ready = value;
      cancel();
      if (!ready) { if (deadline !== null) clearTimer(deadline); deadline = null; pending = null; acknowledgedId = null; last = null; }
      else if (enabled) settle();
    },
    enable(value) {
      if (pending) return false;
      enabled = value;
      cancel();
      if (enabled) settle(); else request(false);
      return true;
    },
    acknowledged(message) {
      const first = pending?.requestId === message.requestId;
      if (!first && message.requestId !== acknowledgedId) return;
      acknowledgedId = message.requestId;
      if (deadline !== null) clearTimer(deadline);
      deadline = null; pending = null;
      const application = message.applies === 'reconnecting' ? 'Reconnecting CarPlay…' : message.applies === 'unchanged' ? 'Current output already matches.' : 'Will apply on the next CarPlay connection.';
      if (message.code) onStatus(message.code === 'reconnectFailed' ? 'Resolution was saved, but CarPlay could not reconnect. Use the manual connection control in DiPlay.' : 'Android could not save this browser resolution. Check DiPlay settings.');
      else onStatus(message.enabled
        ? `Saved browser base ${message.width} × ${message.height}. Android’s resolution percentage still applies.${message.effectiveWidth ? ` Output target ${message.effectiveWidth} × ${message.effectiveHeight}.` : ''} ${application}`
        : `Browser size override disabled. DiPlay’s normal resolution settings apply. ${application}`);
      if (first) settle();
    },
    get pending() { return pending !== null; },
  };
}
