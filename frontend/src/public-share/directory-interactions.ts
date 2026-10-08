export function nativeLinkClick(event: {
  button: number;
  ctrlKey: boolean;
  metaKey: boolean;
  shiftKey: boolean;
  altKey: boolean;
  target: EventTarget | null;
}): boolean {
  const target = event.target as Element | null;
  return Boolean(target?.closest?.('a[href]')
    && (event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey));
}

export function nativeTextSelection(selection: Pick<Selection, 'isCollapsed' | 'toString'> | null): boolean {
  return Boolean(selection && !selection.isCollapsed && selection.toString().trim());
}
