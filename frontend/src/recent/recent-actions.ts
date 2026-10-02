import { notify, postForm, toastError } from '../shared/api/form-api';
import type { BrowserEntry } from '../shared/browser/types';
import type { RecentHistoryState } from './types';

export function createRecentActions({ setSelected, effectiveState, reload }: {
  setSelected: React.Dispatch<React.SetStateAction<Set<string>>>;
  effectiveState: () => RecentHistoryState;
  reload: () => void;
}) {
  const removeEntries = async (entries: BrowserEntry[], {
    confirm = false, isCurrent = () => true,
  }: { confirm?: boolean; isCurrent?: () => boolean } = {}) => {
    if (entries.length === 0 || !isCurrent()) return;
    const paths = entries.map((entry) => entry.path);
    const { query, page } = effectiveState();
    try {
      if (confirm) {
        const confirmed = await window.EnderVault?.askConfirmation({
          title: 'Remove from recent',
          message: `Remove ${paths.length} selected item(s) from recent history? Files and directories will not be deleted.`,
          confirmLabel: 'Remove from recent',
          danger: true,
        });
        if (!confirmed || !isCurrent()) return;
      }
      const body = await postForm('/api/v1/recent/remove', { paths, q: query, page });
      notify(body);
      if (isCurrent()) {
        const removed = new Set(paths);
        setSelected((current) => new Set([...current].filter((path) => !removed.has(path))));
        reload();
      }
    } catch (reason) {
      toastError(reason, 'Recent items could not be removed.');
    }
  };
  return { removeEntries };
}
