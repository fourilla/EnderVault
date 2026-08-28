import { icon } from './BrowserEntries';
import type { BrowserMode, TransferBufferPayload } from './types';
import type { FileBrowserActions } from './useFileActions';

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
  if (!transferBuffer?.active) return null;
  return (
    <section className="transfer-buffer-panel files-react-transfer-buffer" aria-label="Transfer buffer">
      <div className="transfer-buffer-header">
        <div className="transfer-buffer-summary">
          {icon('fas fa-layer-group')}
          <div>
            <strong>Transfer buffer</strong>
            <p>{transferBuffer.count} item(s) ready. Open a directory and choose what to do here.</p>
          </div>
        </div>
        <button className="ghost transfer-buffer-clear" type="button"
          onClick={() => void actions.updateTransferBuffer('clear', {})}>
          Clear buffer
        </button>
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
  );
}
