import { Link } from 'react-router-dom';
import { OverflowMarquee } from '../layout/OverflowMarquee';
import './path-link.css';

export function PathLink({ path, directory, label, onNavigate }: {
  path: string;
  directory: boolean;
  label?: string;
  onNavigate?: () => void;
}) {
  const text = label ?? (path || '/');
  return <Link className="path-link"
    to={`${directory ? '/files' : '/files/detail'}?path=${encodeURIComponent(path)}`}
    title={text}
    onClick={event => {
      if (!onNavigate || event.defaultPrevented || event.button !== 0
        || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
      event.preventDefault();
      onNavigate();
    }}>
    <OverflowMarquee text={text} />
  </Link>;
}
