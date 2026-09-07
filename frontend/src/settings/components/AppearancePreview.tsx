import { useRef, type CSSProperties } from 'react';
import { appearanceTokens, colorPresets, contrastRatio, type Appearance } from '../../shared/appearance/presets';

const notifications = [
  ['info', 'fa-circle-info', 'Download queued'],
  ['success', 'fa-circle-check', 'Changes saved'],
  ['warning', 'fa-triangle-exclamation', 'Storage almost full'],
  ['error', 'fa-circle-exclamation', 'Upload failed'],
] as const;

export function AppearancePreview({ appearance, valid }: { appearance: Appearance; valid: boolean }) {
  // Keep the last valid preview while a HEX value is being typed.
  const lastValid = useRef<Appearance>(valid ? appearance : { ...colorPresets.endervault, controlSize: 'medium', cardSize: 'medium' });
  if (valid) lastValid.current = appearance;
  const value = lastValid.current;
  const style = appearanceTokens(value) as CSSProperties;
  const buttonContrast = contrastRatio(value.button, value.buttonText);
  return <section className="appearance-preview" style={style} aria-label="Appearance preview">
    <h3 className="settings-preview-title">Preview</h3>
    <div className="appearance-preview-samples" inert>
      <div className="appearance-preview-actions">
        <button type="button">Create</button>
        <button type="button" className="ghost appearance-preview-focus">Focus</button>
        <button type="button" className="ghost" disabled>Disabled</button>
      </div>
      <div className="appearance-preview-fields">
        <input type="text" value="Example.txt" readOnly aria-label="Sample filename" />
        <select defaultValue="grid" aria-label="Sample view"><option value="grid">Grid view</option><option value="table">Table view</option></select>
      </div>
      <button type="button" className="settings-spa-nav-item is-active">
        <i className="fas fa-palette" aria-hidden="true" /><span>Appearance</span>
      </button>
      <div className="browser-grid appearance-preview-cards">
        {[false, true].map((selected) => <article key={String(selected)} className={`browser-card${selected ? ' is-selected' : ''}`}>
          <div className="card-thumb"><i className={`fas ${selected ? 'fa-file-image' : 'fa-file-lines'}`} aria-hidden="true" /></div>
          <div className="card-body"><span className="card-name">{selected ? 'Selected.png' : 'Example.txt'}</span>
            <div className="card-meta"><span>{selected ? 'Image' : 'Text'}</span><span>{selected ? '2.4 MB' : '12 KB'}</span></div>
          </div>
        </article>)}
      </div>
      <div className="table-wrap appearance-preview-table"><table>
        <thead><tr><th>Name</th><th>Type</th></tr></thead>
        <tbody><tr><td><span className="item-name">Documents</span></td><td>Directory</td></tr>
          <tr className="is-selected"><td><span className="item-name">Selected.png</span></td><td>Image</td></tr></tbody>
      </table></div>
      <div className="appearance-preview-notifications">
        {notifications.map(([kind, icon, message]) => <div key={kind} className={`toast toast-${kind}`}>
          <i className={`fas ${icon} toast-icon`} aria-hidden="true" /><div className="toast-content"><p className="toast-message">{message}</p></div>
        </div>)}
      </div>
    </div>
    <p className="appearance-preview-contrast">Button contrast: {buttonContrast.toFixed(2)}:1</p>
    {(buttonContrast < 4.5 || contrastRatio(value.buttonHover, value.buttonText) < 4.5) &&
      <p className="appearance-preview-warning" role="status">Low button text contrast.</p>}
    {[value.background, value.panel, value.panelElevated, value.panelMuted].some((background) =>
      contrastRatio(background, value.text) < 4.5 || contrastRatio(background, value.mutedText) < 4.5) &&
      <p className="appearance-preview-warning" role="status">Low contrast between text and backgrounds.</p>}
  </section>;
}
