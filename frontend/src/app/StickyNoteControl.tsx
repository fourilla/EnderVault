import { Link } from 'react-router-dom';
import { AppNavigationLink } from './AppNavigationLink';
import { ShellPopover } from './ShellPopover';

export function StickyNoteControl() {
  return (
    <div className="sticky-note-controls" data-sticky-note-controls hidden>
      <ShellPopover
        id="sticky-notes"
        icon="fas fa-note-sticky"
        label="Sticky notes"
        triggerClassName="sticky-note-visibility-toggle is-active"
        triggerDataAttributes={{ 'data-sticky-note-trigger': '' }}
      >
        <strong className="topbar-control-title">Sticky notes</strong>
        <p>Page-specific notes with a 10,000 character limit.</p>
        <div className="topbar-control-status">
          <span>Status</span>
          <strong className="is-active" data-sticky-note-visibility-status>Visible</strong>
        </div>
        <div className="topbar-control-menu-actions">
          <Link className="ghost button-link icon-text-button" to="/admin/settings?section=appearance#sticky-note-theme">
            <i className="fas fa-palette" aria-hidden="true" />
            <span>Note appearance</span>
          </Link>
          <AppNavigationLink className="ghost button-link icon-text-button" href="/admin/sticky-notes">
            <i className="fas fa-list" aria-hidden="true" />
            <span>Note list</span>
          </AppNavigationLink>
          <button className="ghost icon-text-button" type="button" data-sticky-note-visibility aria-pressed="true">
            <i className="fas fa-eye-slash" aria-hidden="true" />
            <span data-sticky-note-visibility-label>Hide sticky notes</span>
          </button>
          <button className="ghost icon-text-button" type="button" data-sticky-note-add>
            <i className="fas fa-plus" aria-hidden="true" />
            <span>New sticky note</span>
          </button>
        </div>
      </ShellPopover>
    </div>
  );
}
