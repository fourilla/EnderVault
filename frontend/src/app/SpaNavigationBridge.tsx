import { useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { isSpaNavigationUrl } from './navigation';

export function SpaNavigationBridge() {
  const navigate = useNavigate();

  useEffect(() => {
    const navigateWithinShell = (url: string) => {
      if (!isSpaNavigationUrl(url)) return false;
      const destination = new URL(url, window.location.origin);
      navigate(`${destination.pathname}${destination.search}${destination.hash}`);
      return true;
    };

    const followRegisteredRoute = (event: globalThis.MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0
          || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;

      const target = event.target;
      if (!(target instanceof Element)) return;
      const anchor = target.closest<HTMLAnchorElement>('a[href]');
      if (!anchor || anchor.hasAttribute('download') || anchor.hasAttribute('data-document-navigation')) return;
      if (anchor.target && anchor.target.toLowerCase() !== '_self') return;

      const href = anchor.getAttribute('href');
      if (!href || href.startsWith('#') || !isSpaNavigationUrl(href)) return;
      event.preventDefault();
      navigateWithinShell(anchor.href);
    };

    const followNavigationRequest = (event: Event) => {
      const request = event as CustomEvent<{ url?: string }>;
      if (!request.detail?.url || !navigateWithinShell(request.detail.url)) return;
      request.preventDefault();
    };

    document.addEventListener('click', followRegisteredRoute);
    document.addEventListener('endervault:navigate', followNavigationRequest);
    return () => {
      document.removeEventListener('click', followRegisteredRoute);
      document.removeEventListener('endervault:navigate', followNavigationRequest);
    };
  }, [navigate]);

  return null;
}
