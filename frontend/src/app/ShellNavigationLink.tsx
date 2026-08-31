import { Link, NavLink } from 'react-router-dom';
import { isSpaNavigationUrl, type NavigationEntry } from './navigation';

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

  if (entry.surface === 'spa') {
    return (
      <NavLink
        className={({ isActive }) => `${className ?? ''}${isActive ? ' active' : ''}`.trim()}
        end
        onClick={onNavigate}
        to={entry.path}
      >
        {content}
      </NavLink>
    );
  }

  if (isSpaNavigationUrl(entry.path)) {
    return <Link className={className} onClick={onNavigate} to={entry.path}>{content}</Link>;
  }

  return <a className={className} href={entry.path} onClick={onNavigate}>{content}</a>;
}
