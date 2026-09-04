interface ActivityPopover {
  activeId: () => string | null;
  show: () => void;
}

export function observeActivityStarts(popover: ActivityPopover) {
  let frame: number | null = null;
  const pendingIds = new Set<string>();
  const cancelPending = () => {
    if (frame !== null) window.cancelAnimationFrame(frame);
    frame = null;
    pendingIds.clear();
  };
  const canShow = () => document.visibilityState === 'visible'
    && !document.fullscreenElement
    && !document.querySelector('dialog[open], [aria-modal="true"]:not([hidden]), '
      + '.is-editor-fullscreen, .is-comic-fullscreen, .is-image-fullscreen')
    && (popover.activeId() === null || popover.activeId() === 'activity');

  const onStarted = (event: Event) => {
    const ids = (event as CustomEvent<{ ids?: string[] }>).detail?.ids;
    if (!Array.isArray(ids) || ids.length === 0) return;
    ids.forEach((id) => pendingIds.add(id));
    if (frame !== null) return;
    // Let the initiating action close its dialog and publish the task rows first.
    frame = window.requestAnimationFrame(() => {
      frame = null;
      const hasItems = window.EnderVaultActivity?.snapshot().items.some((item) => pendingIds.has(item.id));
      pendingIds.clear();
      if (hasItems && canShow()) popover.show();
    });
  };
  const onVisibility = () => {
    if (document.visibilityState !== 'visible') cancelPending();
  };

  document.addEventListener('endervault:activity-started', onStarted);
  document.addEventListener('pointerdown', cancelPending);
  document.addEventListener('keydown', cancelPending);
  document.addEventListener('visibilitychange', onVisibility);
  window.addEventListener('blur', cancelPending);
  return () => {
    cancelPending();
    document.removeEventListener('endervault:activity-started', onStarted);
    document.removeEventListener('pointerdown', cancelPending);
    document.removeEventListener('keydown', cancelPending);
    document.removeEventListener('visibilitychange', onVisibility);
    window.removeEventListener('blur', cancelPending);
  };
}
