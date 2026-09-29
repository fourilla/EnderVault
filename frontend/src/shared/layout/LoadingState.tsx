import './loading-state.css';

export function LoadingState({ label = 'Loading page...', compact = false, className = '' }: {
  label?: string;
  compact?: boolean;
  className?: string;
}) {
  return <div className={`loading-state${compact ? ' loading-state-compact' : ''}${className ? ` ${className}` : ''}`}
    role="status" aria-live="polite">
    <i className="fas fa-spinner fa-spin" aria-hidden="true" />
    <span>{label}</span>
  </div>;
}
