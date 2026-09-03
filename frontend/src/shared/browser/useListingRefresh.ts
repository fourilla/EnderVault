import { useEffect } from 'react';

export function useListingRefresh(reload: () => void) {
  useEffect(() => {
    // Task destinations identify changed data, not a request to leave the current view.
    const bridge = {
      refreshListing: async (_url?: string) => { reload(); },
      requestListingRefresh: (_url?: string) => { reload(); },
      syncToolbarState: () => undefined,
    };
    window.EnderVaultFileBrowser = bridge;
    document.dispatchEvent(new CustomEvent('endervault:files-ready'));
    return () => {
      if (window.EnderVaultFileBrowser === bridge) delete window.EnderVaultFileBrowser;
    };
  }, [reload]);
}
