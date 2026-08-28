import { useEffect, useState } from 'react';
import { icon } from './BrowserEntries';
import type { BrowserMode, TransferBufferPayload } from './types';
import type { FileBrowserActions } from './useFileActions';

const MINIMIZED_STORAGE_KEY = 'endervault.transferBuffer.minimized';

const initialMinimized = () => {
  try {
    return window.sessionStorage.getItem(MINIMIZED_STORAGE_KEY) === 'true';
  } catch {
    return false;
  }
};

export function TransferBufferPanel({
  transferBuffer,
  mode,
  path,
  actions,
}: {
  transferBuffer: TransferBufferPayload | null;
  mode: BrowserMode | undefined;
  path: string;
  actions: FileBrowserActions;
}) {
  const [minimized, setMinimized] = useState(initialMinimized);
  const active = Boolean(transferBuffer?.active);

  useEffect(() => {
    document.body.classList.toggle('transfer-buffer-dock-enabled', active);
    return () => document.body.classList.remove('transfer-buffer-dock-enabled');
  }, [active]);

  const toggleMinimized = () => {
    const next = !minimized;
    setMinimized(next);
    try {
      window.sessionStorage.setItem(MINIMIZED_STORAGE_KEY, String(next));
    } catch {
      // The dock remains usable when session storage is unavailable.
    }
  };

  if (!transferBuffer?.active) return null;
  return (
    <div data-transfer-buffer-region>
      <section className={'transfer-buffer-panel files-react-transfer-buffer'
        + (minimized ? ' is-minimized' : '')} aria-label="Transfer buffer">
        <div className="transfer-buffer-header">
          <div className="transfer-buffer-summary">
            {icon('fas fa-layer-group')}
            <div>
              <strong>Transfer buffer</strong>
              <p>{transferBuffer.count} item(s) ready. Open a directory and choose what to do here.</p>
            </div>
          </div>
          <div className="transfer-buffer-header-controls">
            <button className="ghost transfer-buffer-clear" type="button"
              onClick={() => void actions.updateTransferBuffer('clear', {})}>
              Clear buffer
            </button>
            <button className="ghost icon-button transfer-buffer-toggle" type="button"
              title={minimized ? 'Expand transfer buffer' : 'Minimize transfer buffer'}
              aria-label={minimized ? 'Expand transfer buffer' : 'Minimize transfer buffer'}
              aria-expanded={!minimized} onClick={toggleMinimized}>
              {icon(minimized ? 'fas fa-chevron-up' : 'fas fa-chevron-down')}
            </button>
          </div>
        </div>
        <ul className="transfer-buffer-list">
          {transferBuffer.items.map((item) => (
            <li key={item.path}>
              {icon(item.iconClass)}
              <span title={item.path}>{item.name}</span>
              <button className="transfer-buffer-remove" type="button"
                title={'Remove ' + item.name + ' from transfer buffer'}
                aria-label={'Remove ' + item.name + ' from transfer buffer'}
                onClick={() => void actions.updateTransferBuffer('remove', { itemPath: item.path })}>
                {icon('fas fa-xmark')}
              </button>
            </li>
          ))}
        </ul>
        {mode === 'browse' && (
          <div className="transfer-buffer-actions">
            <button className="ghost" type="button" onClick={() => void actions.updateTransferBuffer('paste', {
              path, operation: 'move', conflictPolicy: 'ask',
            })}>
              {icon('fas fa-file-import')}
              <span>Move here</span>
            </button>
            <button className="ghost" type="button" onClick={() => void actions.updateTransferBuffer('paste', {
              path, operation: 'copy', conflictPolicy: 'ask',
            })}>
              {icon('fas fa-copy')}
              <span>Copy here</span>
            </button>
          </div>
        )}
      </section>
    </div>
  );
}
