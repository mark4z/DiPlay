// Embedded display chrome only. Never listen on the video or synthesize a click.
export function clampPosition(x, y, bounds, size) {
  return {
    x: Math.max(bounds.left, Math.min(x, Math.max(bounds.left, bounds.right - size.width))),
    y: Math.max(bounds.top, Math.min(y, Math.max(bounds.top, bounds.bottom - size.height))),
  };
}

export function floatingControls({ panel, grip, reveal, settings, safeArea, document, window,
  idleMs = 4000, setTimer = setTimeout, clearTimer = clearTimeout }) {
  let timer = null, drag = null, position = null, pinned = true;
  const focused = () => panel.contains(document.activeElement) || reveal === document.activeElement;
  function cancelTimer() { if (timer !== null) clearTimer(timer); timer = null; }
  function arm() {
    cancelTimer();
    if (pinned || drag || settings.open || focused() || document.visibilityState !== 'visible') return;
    timer = setTimer(() => {
      timer = null;
      if (pinned || drag || settings.open || focused() || document.visibilityState !== 'visible') return;
      panel.hidden = true;
      reveal.hidden = false;
      reveal.setAttribute('aria-expanded', 'false');
    }, idleMs);
  }
  function show() {
    panel.hidden = false;
    reveal.hidden = true;
    reveal.setAttribute('aria-expanded', 'true');
    layout();
    arm();
  }
  function bounds() {
    const safe = safeArea.getBoundingClientRect();
    const visual = window.visualViewport;
    return {
      left: Math.max(safe.left, visual?.offsetLeft || 0),
      top: Math.max(safe.top, visual?.offsetTop || 0),
      right: Math.min(safe.right, visual ? visual.offsetLeft + visual.width : safe.right),
      bottom: Math.min(safe.bottom, visual ? visual.offsetTop + visual.height : safe.bottom),
    };
  }
  function layout() {
    const area = bounds();
    panel.style.maxWidth = `${Math.max(0, area.right - area.left)}px`;
    panel.style.maxHeight = `${Math.max(0, area.bottom - area.top)}px`;
    // Hidden controls cannot be measured; re-clamp when revealed.
    if (panel.hidden) return;
    const size = panel.getBoundingClientRect();
    position = clampPosition(position?.x ?? area.right - size.width, position?.y ?? area.top, area, size);
    panel.style.left = `${position.x}px`;
    panel.style.top = `${position.y}px`;
  }
  function endDrag(event) {
    if (!drag || (event && event.pointerId !== drag.id)) return;
    const id = drag.id;
    drag = null;
    grip.removeAttribute('data-dragging');
    if (grip.hasPointerCapture?.(id)) grip.releasePointerCapture(id);
    layout();
    arm();
  }
  grip.addEventListener('pointerdown', event => {
    if (drag || event.isPrimary === false || (event.pointerType === 'mouse' && event.button !== 0)) return;
    event.preventDefault();
    layout();
    try { grip.setPointerCapture(event.pointerId); } catch { return; }
    drag = { id: event.pointerId, x: event.clientX, y: event.clientY, origin: { ...position } };
    grip.setAttribute('data-dragging', 'true');
    cancelTimer();
  });
  grip.addEventListener('pointermove', event => {
    if (!drag || event.pointerId !== drag.id) return;
    if (event.pointerType === 'mouse' && event.buttons === 0) { endDrag(event); return; }
    event.preventDefault();
    position = { x: drag.origin.x + event.clientX - drag.x, y: drag.origin.y + event.clientY - drag.y };
    layout();
  });
  for (const name of ['pointerup', 'pointercancel', 'lostpointercapture']) grip.addEventListener(name, endDrag);
  grip.addEventListener('keydown', event => {
    const moves = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] };
    const move = moves[event.key];
    if (!move) return;
    event.preventDefault();
    layout();
    const step = event.shiftKey ? 40 : 10;
    position = { x: position.x + move[0] * step, y: position.y + move[1] * step };
    layout();
  });
  reveal.addEventListener('click', () => { show(); grip.focus({ preventScroll: true }); });
  for (const name of ['pointerdown', 'pointermove', 'keydown', 'input', 'change']) panel.addEventListener(name, arm);
  panel.addEventListener('focusin', cancelTimer);
  panel.addEventListener('focusout', () => { cancelTimer(); timer = setTimer(arm, 0); });
  settings.addEventListener('toggle', () => { if (settings.open) show(); else arm(); });
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') { endDrag(); cancelTimer(); } else show();
  });
  window.addEventListener('blur', () => { endDrag(); cancelTimer(); });
  window.addEventListener('focus', arm);
  window.addEventListener('pagehide', () => { endDrag(); cancelTimer(); });
  for (const name of ['resize', 'orientationchange']) window.addEventListener(name, () => { endDrag(); layout(); });
  document.addEventListener('fullscreenchange', () => { endDrag(); layout(); });
  for (const name of ['resize', 'scroll']) window.visualViewport?.addEventListener(name, () => { endDrag(); layout(); });
  if (window.ResizeObserver) new window.ResizeObserver(layout).observe(panel);
  show();
  return { update({ critical }) { pinned = Boolean(critical); if (pinned) show(); else arm(); } };
}
