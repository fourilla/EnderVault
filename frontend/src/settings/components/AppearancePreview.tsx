import { useRef, type CSSProperties } from 'react';
import { appearanceTokens, colorPresets, contrastRatio, type Appearance } from '../../shared/appearance/presets';
import { describePreviewTarget, previewTargetAt, type AppearanceColor } from '../appearancePreviewTargets';

const notifications = [
  ['info', 'fa-circle-info', 'Download queued'],
  ['success', 'fa-circle-check', 'Changes saved'],
  ['warning', 'fa-triangle-exclamation', 'Storage almost full'],
  ['error', 'fa-circle-exclamation', 'Upload failed'],
] as const;

export function AppearancePreview({ appearance, valid, onInspectColor }: {
  appearance: Appearance; valid: boolean; onInspectColor: (field: AppearanceColor) => void;
}) {
  // Keep the last valid preview while a HEX value is being typed.
  const lastValid = useRef<Appearance>(valid ? appearance : { ...colorPresets.endervault, controlSize: 'medium', cardSize: 'medium' });
  if (valid) lastValid.current = appearance;
  const value = lastValid.current;
  const style = appearanceTokens(value) as CSSProperties;
  const buttonContrast = contrastRatio(value.button, value.buttonText);
  return <section className="appearance-preview" style={style} aria-label="Appearance preview"
    data-preview-color="background" data-preview-border="border"
    onPointerMove={(event) => {
      const target = describePreviewTarget(previewTargetAt(event.currentTarget, event.target, event.clientX, event.clientY));
      event.currentTarget.title = target.title;
      event.currentTarget.style.cursor = target.field ? 'pointer' : 'help';
    }}
    onPointerDownCapture={(event) => { event.preventDefault(); }}
    onClickCapture={(event) => {
      event.preventDefault();
      event.stopPropagation();
      const target = describePreviewTarget(previewTargetAt(event.currentTarget, event.target, event.clientX, event.clientY));
      if (target.field) onInspectColor(target.field);
    }}>
    <h3 className="settings-preview-title" data-preview-color="mutedText">Preview</h3>
    <div className="appearance-preview-samples">
      <div className="appearance-preview-actions">
        <button type="button" tabIndex={-1} className="appearance-preview-primary" data-preview-color="button" data-preview-border="button"><span data-preview-color="buttonText">Normal</span></button>
        <button type="button" tabIndex={-1} className="appearance-preview-hover" data-preview-color="buttonHover" data-preview-border="buttonHover"><span data-preview-color="buttonText">Hover</span></button>
        <span className="appearance-preview-focus-target" data-preview-color="accentStrong"><button type="button" tabIndex={-1} className="appearance-preview-primary appearance-preview-focus" data-preview-color="button" data-preview-border="button"><span data-preview-color="buttonText">Focus</span></button></span>
        <button type="button" tabIndex={-1} className="ghost appearance-preview-disabled" aria-disabled="true" data-preview-color="panel" data-preview-border="border"><span data-preview-color="text">Disabled</span></button>
      </div>
      <div className="appearance-preview-fields">
        <input type="text" tabIndex={-1} data-preview-color="panelElevated" data-preview-border="border" value="Example.txt" readOnly aria-label="Sample filename" />
        <select tabIndex={-1} data-preview-color="panelElevated" data-preview-border="border" defaultValue="grid" aria-label="Sample view"><option value="grid">Grid view</option><option value="table">Table view</option></select>
      </div>
      <button type="button" tabIndex={-1} className="settings-spa-nav-item is-active" data-preview-color="selection" data-preview-border="selection">
        <i className="fas fa-palette" aria-hidden="true" data-preview-color="accentStrong" /><span data-preview-color="text">Appearance</span>
      </button>
      <div className="browser-grid appearance-preview-cards">
        {[false, true].map((selected) => <article key={String(selected)} className={`browser-card${selected ? ' is-selected' : ''}`}
          data-preview-color={selected ? 'selection' : 'panel'} data-preview-border={selected ? 'accent' : 'border'}>
          <div className="card-thumb" data-preview-color="panelMuted"><i className={`fas ${selected ? 'fa-file-image' : 'fa-file-lines'}`} aria-hidden="true" data-preview-color="text" /></div>
          <div className="card-body"><span className="card-name" data-preview-color={selected ? 'accentStrong' : 'text'}>{selected ? 'Selected.png' : 'Example.txt'}</span>
            <div className="card-meta"><span data-preview-color="mutedText">{selected ? 'Image' : 'Text'}</span><span data-preview-color="mutedText">{selected ? '2.4 MB' : '12 KB'}</span></div>
          </div>
        </article>)}
      </div>
      <div className="table-wrap appearance-preview-table" data-preview-color="panel" data-preview-border="border"><table>
        <thead data-preview-color="panelMuted"><tr><th data-preview-border="border"><span data-preview-color="text">Name</span></th><th data-preview-border="border"><span data-preview-color="text">Type</span></th></tr></thead>
        <tbody><tr><td data-preview-border="border"><span className="item-name" data-preview-color="text">Documents</span></td><td data-preview-border="border"><span data-preview-color="text">Directory</span></td></tr>
          <tr className="is-selected" data-preview-color="selection"><td><span className="item-name" data-preview-color="accentStrong">Selected.png</span></td><td><span data-preview-color="text">Image</span></td></tr></tbody>
      </table></div>
      <div className="appearance-preview-notifications">
        {notifications.map(([kind, icon, message]) => <div key={kind} className={`toast toast-${kind}`} data-preview-color="panelElevated" data-preview-border="border"
          data-preview-left-border={kind === 'info' ? 'accent' : kind === 'error' ? 'danger' : kind}>
          <i className={`fas ${icon} toast-icon`} aria-hidden="true" data-preview-color={kind === 'info' ? 'accentStrong' : kind === 'error' ? 'danger' : kind} /><div className="toast-content"><p className="toast-message"><span data-preview-color="text">{message}</span></p></div>
        </div>)}
      </div>
    </div>
    <p className="appearance-preview-contrast" data-preview-color="mutedText">Button contrast: {buttonContrast.toFixed(2)}:1</p>
    {(buttonContrast < 4.5 || contrastRatio(value.buttonHover, value.buttonText) < 4.5) &&
      <p className="appearance-preview-warning" role="status" data-preview-color="warning">Low button text contrast.</p>}
    {[value.background, value.panel, value.panelElevated, value.panelMuted].some((background) =>
      contrastRatio(background, value.text) < 4.5 || contrastRatio(background, value.mutedText) < 4.5) &&
      <p className="appearance-preview-warning" role="status" data-preview-color="warning">Low contrast between text and backgrounds.</p>}
  </section>;
}
