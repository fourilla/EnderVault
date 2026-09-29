import { Link, NavLink } from 'react-router-dom';
import type { NavigationEntry } from './navigation';

interface ShellNavigationLinkProps {
  entry: NavigationEntry;
  className?: string;
  onNavigate?: () => void;
}

export function ShellNavigationLink({ entry, className, onNavigate }: ShellNavigationLinkProps) {
  const content = (
    <>
      <i className={entry.icon} aria-hidden="true" />
      <span>{entry.label}</span>
    </>
  );

  if (entry.surface === 'standalone') {
    return <a className={className} href={entry.path} target="_blank" rel="noopener noreferrer"
      data-document-navigation title={`${entry.label} (opens in a new tab)`} onClick={onNavigate}>
      {content}
      <i className="fas fa-arrow-up-right-from-square" aria-hidden="true" />
      <span className="visually-hidden"> (opens in a new tab)</span>
    </a>;
  }

  if (entry.surface === 'spa') {
    return (
      <NavLink
        className={({ isActive }) => `${className ?? ''}${isActive ? ' active' : ''}`.trim()}
        end
        title={entry.label}
        onClick={onNavigate}
        to={entry.path}
      >
        {content}
      </NavLink>
    );
  }

  return <Link className={className} title={entry.label} onClick={onNavigate} to={entry.path}>{content}</Link>;
}
