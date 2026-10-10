// Embedded display chrome only. Observe page activity without consuming video input.
export function clampPosition(x, y, bounds, size) {
  return {
    x: Math.max(bounds.left, Math.min(x, Math.max(bounds.left, bounds.right - size.width))),
    y: Math.max(bounds.top, Math.min(y, Math.max(bounds.top, bounds.bottom - size.height))),
  };
}

export function floatingControls({ panel, grip, reveal, settings, safeArea, document, window,
  idleMs = 4000, setTimer = setTimeout, clearTimer = clearTimeout }) {
  let timer = null, drag = null, position = null, pinned = true, pointerFocus = false;
  const contacts = new Set();
  const focused = () => reveal === document.activeElement || (panel.contains(document.activeElement) &&
    (!pointerFocus || document.activeElement?.matches?.('input:not([type="checkbox"]):not([type="radio"]), textarea, select, [contenteditable="true"]')));
  function cancelTimer() { if (timer !== null) clearTimer(timer); timer = null; }
  function arm() {
    cancelTimer();
    if (panel.hidden || pinned || drag || contacts.size || settings.open || focused() || document.visibilityState !== 'visible') return;
    timer = setTimer(() => {
      timer = null;
      if (panel.hidden || pinned || drag || contacts.size || settings.open || focused() || document.visibilityState !== 'visible') return;
      // Pointer-clicked buttons must not pin the bar forever. Preserve keyboard
      // focus and text editing, but release stale pointer focus before hiding.
      if (panel.contains(document.activeElement)) document.activeElement.blur?.();
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
  // Passive capture observes the original event, never prevents it, replays it,
  // or takes pointer capture away from CarPlay. The newly visible chrome cannot
  // steal a second finger while the current canvas gesture is in progress.
  document.addEventListener('pointerdown', event => {
    if (event.pointerType === 'mouse' && event.button !== 0) return;
    pointerFocus = true;
    if (!panel.contains(event.target) && event.target !== reveal) {
      contacts.add(event.pointerId);
      panel.setAttribute('data-display-gesture', 'true');
    }
    show();
  }, { capture: true, passive: true });
  function finishContact(event) {
    if (!contacts.delete(event.pointerId)) return;
    if (!contacts.size) panel.removeAttribute('data-display-gesture');
    arm();
  }
  for (const name of ['pointerup', 'pointercancel', 'lostpointercapture'])
    document.addEventListener(name, finishContact, { capture: true, passive: true });
  document.addEventListener('keydown', () => { pointerFocus = false; arm(); }, { capture: true });
  // The visually hidden reveal button remains in the accessibility/tab order.
  reveal.addEventListener('focus', () => { pointerFocus = false; show(); grip.focus({ preventScroll: true }); cancelTimer(); });
  reveal.addEventListener('click', () => { pointerFocus = false; show(); grip.focus({ preventScroll: true }); });
  for (const name of ['pointerdown', 'pointermove', 'keydown', 'input', 'change']) panel.addEventListener(name, arm);
  panel.addEventListener('focusin', arm);
  panel.addEventListener('focusout', () => { cancelTimer(); timer = setTimer(arm, 0); });
  settings.addEventListener('toggle', () => { if (settings.open) show(); else arm(); });
  function resetContacts() { contacts.clear(); panel.removeAttribute('data-display-gesture'); }
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') { resetContacts(); endDrag(); cancelTimer(); } else show();
  });
  window.addEventListener('blur', () => { resetContacts(); endDrag(); cancelTimer(); });
  window.addEventListener('focus', arm);
  window.addEventListener('pagehide', () => { resetContacts(); endDrag(); cancelTimer(); });
  for (const name of ['resize', 'orientationchange']) window.addEventListener(name, () => { endDrag(); layout(); });
  document.addEventListener('fullscreenchange', () => { endDrag(); layout(); });
  for (const name of ['resize', 'scroll']) window.visualViewport?.addEventListener(name, () => { endDrag(); layout(); });
  if (window.ResizeObserver) new window.ResizeObserver(layout).observe(panel);
  show();
  return { update({ critical }) { pinned = Boolean(critical); if (pinned) show(); else arm(); } };
}

