export function StickyNoteControl() {
  return (
    <div className="topbar-control sticky-note-controls" data-sticky-note-controls hidden>
      <button
        className="ghost icon-button topbar-control-trigger sticky-note-visibility-toggle"
        type="button"
        data-sticky-note-visibility
        title="Hide sticky notes"
        aria-label="Hide sticky notes"
        aria-pressed="true"
        aria-haspopup="dialog"
      >
        <i className="fas fa-note-sticky" aria-hidden="true" />
      </button>
      <div className="topbar-control-popover">
        <div className="topbar-control-menu" role="dialog" aria-label="Sticky note controls">
          <strong className="topbar-control-title">Sticky notes</strong>
          <p>Page-specific notes with a 10,000 character limit.</p>
          <div className="topbar-control-status">
            <span>Status</span>
            <strong className="is-active" data-sticky-note-visibility-status>Visible</strong>
          </div>
          <div className="topbar-control-menu-actions">
            <a className="ghost button-link icon-text-button" href="/admin/settings?section=appearance#sticky-note-theme">
              <i className="fas fa-palette" aria-hidden="true" />
              <span>Note appearance</span>
            </a>
            <a className="ghost button-link icon-text-button" href="/admin/sticky-notes">
              <i className="fas fa-list" aria-hidden="true" />
              <span>Note list</span>
            </a>
            <button className="ghost icon-text-button" type="button" data-sticky-note-add>
              <i className="fas fa-plus" aria-hidden="true" />
              <span>New sticky note</span>
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
