import { appearanceColorFields } from '../shared/appearance/presets';

export type AppearanceColor = keyof typeof appearanceColorFields;

export function describePreviewTarget(target: string): { field?: AppearanceColor; title: string } {
  if (Object.hasOwn(appearanceColorFields, target)) {
    const field = target as AppearanceColor;
    return { field, title: appearanceColorFields[field] };
  }
  if (target === 'selection') return { field: 'accent', title: 'Accent - blended with Panel background' };
  if (target === 'success') return { title: 'Success - fixed status color' };
  if (target === 'warning') return { title: 'Warning - fixed status color' };
  if (target === 'danger') return { title: 'Error - fixed status color' };
  return { title: 'Fixed sample styling' };
}

export function previewTargetAt(root: HTMLElement, target: EventTarget | null, x: number, y: number): string {
  if (!(target instanceof Element)) return 'background';
  const ancestors: HTMLElement[] = [];
  for (let node: Element | null = target; node && root.contains(node); node = node.parentElement) {
    if (node instanceof HTMLElement) ancestors.push(node);
    if (node === root) break;
  }
  // Border hits take precedence over the background underneath them.
  for (const node of ancestors) {
    if (!node.dataset.previewBorder) continue;
    const rect = node.getBoundingClientRect();
    const css = getComputedStyle(node);
    if (node.dataset.previewLeftBorder && x >= rect.left && x < rect.left + parseFloat(css.borderLeftWidth) && y >= rect.top && y <= rect.bottom) {
      return node.dataset.previewLeftBorder;
    }
    if (x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom &&
        (x < rect.left + parseFloat(css.borderLeftWidth) || x > rect.right - parseFloat(css.borderRightWidth) ||
         y < rect.top + parseFloat(css.borderTopWidth) || y > rect.bottom - parseFloat(css.borderBottomWidth))) {
      return node.dataset.previewBorder;
    }
  }
  return ancestors.find((node) => node.dataset.previewColor)?.dataset.previewColor ?? 'background';
}
