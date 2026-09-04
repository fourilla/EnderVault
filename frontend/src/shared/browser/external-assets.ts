const scriptPromises = new Map<string, Promise<void>>();

export const loadScript = (src: string) => {
  const existing = scriptPromises.get(src);
  if (existing) return existing;
  const promise = new Promise<void>((resolve, reject) => {
    const loaded = document.querySelector<HTMLScriptElement>(`script[src="${src}"]`);
    if (loaded) {
      resolve();
      return;
    }
    const script = document.createElement('script');
    script.src = src;
    script.async = true;
    script.addEventListener('load', () => resolve(), { once: true });
    script.addEventListener('error', () => reject(new Error(`Could not load ${src}`)), { once: true });
    document.head.append(script);
  });
  scriptPromises.set(src, promise);
  return promise;
};

export const loadStyle = (href: string) => {
  if (document.querySelector(`link[href="${href}"]`)) return;
  const link = document.createElement('link');
  link.rel = 'stylesheet';
  link.href = href;
  document.head.append(link);
};
