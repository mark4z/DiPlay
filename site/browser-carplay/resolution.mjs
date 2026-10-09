// Browser rendering-device pixels, never decoded-frame dimensions or panel-native claims.
// Android alone applies the user's existing resolution percentage and encoder alignment.
export const RESOLUTION_UNITS = 'device-pixels';

export function renderPixelSize(rect, dpr = 1, deviceBox = null, writingMode = 'horizontal-tb') {
  if (!rect || !Number.isFinite(rect.width) || !Number.isFinite(rect.height) || rect.width <= 0 || rect.height <= 0 ||
      !Number.isFinite(dpr) || dpr <= 0 || dpr > 16) return null;
  const box = Array.isArray(deviceBox) ? (deviceBox.length === 1 ? deviceBox[0] : null) : deviceBox;
  const vertical = /^(vertical|sideways)-/.test(writingMode);
  if (box && Number.isSafeInteger(box.inlineSize) && Number.isSafeInteger(box.blockSize) &&
      box.inlineSize > 0 && box.blockSize > 0) {
    return browserSize({ width: vertical ? box.blockSize : box.inlineSize,
      height: vertical ? box.inlineSize : box.blockSize });
  }
  return browserSize({ width: rect.width * dpr, height: rect.height * dpr });
}

// Observe the video container, not decoded frames or overlay toolbar dimensions.
export function observeRenderPixels(element, changed, env = window) {
  let observed = null, query = null;
  const style = () => env.getComputedStyle(element);
  const ratio = () => env.devicePixelRatio ?? 1;
  const content = () => {
    const css = style();
    const number = key => parseFloat(css[key]) || 0;
    let width = parseFloat(css.width), height = parseFloat(css.height);
    if (css.boxSizing === 'border-box') {
      width -= number('paddingLeft') + number('paddingRight') + number('borderLeftWidth') + number('borderRightWidth');
      height -= number('paddingTop') + number('paddingBottom') + number('borderTopWidth') + number('borderBottomWidth');
    }
    return { width, height };
  };
  const measure = () => {
    // Hidden/disconnected elements can retain fixed computed CSS dimensions.
    if (element.getClientRects && element.getClientRects().length === 0) return null;
    const rect = content(), dpr = ratio();
    const exact = observed && observed.dpr === dpr &&
      Math.abs(observed.rect.width - rect.width) < .01 && Math.abs(observed.rect.height - rect.height) < .01;
    return renderPixelSize(rect, dpr, exact ? observed.box : null, style().writingMode);
  };
  if (env.ResizeObserver) {
    const observer = new env.ResizeObserver(entries => {
      const entry = entries.find(value => value.target === element);
      if (!entry) return;
      observed = { rect: entry.contentRect, box: entry.devicePixelContentBoxSize, dpr: ratio() };
      changed();
    });
    try { observer.observe(element, { box: 'device-pixel-content-box' }); }
    catch { observer.observe(element, { box: 'content-box' }); }
  }
  const densityChanged = () => { observed = null; watchDensity(); changed(); };
  function watchDensity() {
    if (!env.matchMedia) return;
    if (query?.removeEventListener) query.removeEventListener('change', densityChanged);
    else query?.removeListener?.(densityChanged);
    query = env.matchMedia(`(resolution: ${ratio()}dppx)`);
    if (query.addEventListener) query.addEventListener('change', densityChanged);
    else query.addListener?.(densityChanged);
  }
  watchDensity();
  return measure;
}
export function browserSize(rect) {
  const width = Math.round(rect?.width), height = Math.round(rect?.height);
  if (!Number.isSafeInteger(width) || !Number.isSafeInteger(height) || Math.min(width, height) < 320 ||
      Math.max(width, height) > 16384 || width / height > 3 || height / width > 3) return null;
  return { width, height };
}

export function followBrowserResolution({ measure, send, onStatus, onTarget = () => {}, setTimer = setTimeout, clearTimer = clearTimeout }) {
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
    onTarget(null);
    pending = { requestId, key };
    last = key;
    if (!send({ requestId, enabled: follow, ...(size ? { ...size, units: RESOLUTION_UNITS } : {}) })) {
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
      if (!ready) { onTarget(null); if (deadline !== null) clearTimer(deadline); deadline = null; pending = null; acknowledgedId = null; last = null; }
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
      // Only a correlated Android acknowledgment can label a target. Never infer
      // the effective output from raw viewport pixels or the decoded video.
      onTarget(!message.code || message.code === 'reconnectFailed'
        ? (Number.isSafeInteger(message.effectiveWidth) && Number.isSafeInteger(message.effectiveHeight)
          ? { width: message.effectiveWidth, height: message.effectiveHeight } : null) : null);
      if (deadline !== null) clearTimer(deadline);
      deadline = null; pending = null;
      const application = message.applies === 'reconnecting' ? 'Reconnecting CarPlay…' : message.applies === 'unchanged' ? 'Current output already matches.' : 'Will apply on the next CarPlay connection.';
      if (message.code) onStatus(message.code === 'reconnectFailed' ? 'Resolution was saved, but CarPlay could not reconnect. Use the manual connection control in DiPlay.' : 'Android could not save this browser resolution. Check DiPlay settings.');
      else onStatus(message.enabled
        ? `Saved browser base ${message.width} × ${message.height} device pixels. Android’s resolution percentage still applies.${message.effectiveWidth ? ` Output target ${message.effectiveWidth} × ${message.effectiveHeight}.` : ''} ${application}`
        : `Browser size override disabled. DiPlay’s normal resolution settings apply. ${application}`);
      if (first) settle();
    },
    get pending() { return pending !== null; },
  };
}

