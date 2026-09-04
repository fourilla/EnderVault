// A refresh never creates a scroll request. Only navigation can enqueue one.
export function createScrollRestoration(initialTop: number) {
  let pendingTop: number | null = initialTop;
  return {
    request(top: number) { pendingTop = top; },
    restore(scrollTo: (top: number) => void) {
      if (pendingTop === null) return;
      const top = pendingTop;
      pendingTop = null;
      scrollTo(top);
    },
  };
}

export function createHashRestoration() {
  let navigation = '';
  let handled = false;
  return (key: string, restore: () => boolean) => {
    if (key !== navigation) {
      navigation = key;
      handled = false;
    }
    if (!handled) handled = restore();
  };
}
