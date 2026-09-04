import type { AnchorHTMLAttributes } from 'react';
import { Link } from 'react-router-dom';
import { isSpaNavigationUrl } from './navigation';

interface AppNavigationLinkProps extends Omit<AnchorHTMLAttributes<HTMLAnchorElement>, 'href'> {
  href: string;
}

export function AppNavigationLink({ href, ...props }: AppNavigationLinkProps) {
  if (isSpaNavigationUrl(href)) {
    return <Link {...props} to={href} />;
  }
  return <a {...props} href={href} />;
}
