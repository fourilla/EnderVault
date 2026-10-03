import {
  createContext, type Dispatch, type FormEventHandler, type PropsWithChildren,
  type SetStateAction, useContext, useLayoutEffect, useState,
} from 'react';
import { useLocation } from 'react-router-dom';
import { useTopbarPopover } from './TopbarPopoverContext';
import { useSearchInput } from '../shared/search/useSearchInput';
import { SearchSuggestions } from '../shared/search/SearchSuggestions';

interface SearchControl {
  label: string;
  placeholder?: string;
  value: string;
  onChange: (value: string) => void;
  onSubmit: FormEventHandler<HTMLFormElement>;
  onReset?: () => void;
  disabled?: boolean;
  schemaScope?: string;
  suggestionHidden?: string;
}

interface SearchRegistration extends SearchControl { routeKey: string }

const SearchContext = createContext<SearchRegistration | null>(null);
const SearchRegistrationContext = createContext<Dispatch<SetStateAction<SearchRegistration | null>> | null>(null);

export function RouteSearchProvider({ children }: PropsWithChildren) {
  const [search, setSearch] = useState<SearchRegistration | null>(null);
  return <SearchRegistrationContext.Provider value={setSearch}>
    <SearchContext.Provider value={search}>{children}</SearchContext.Provider>
  </SearchRegistrationContext.Provider>;
}

// Pages own their query and navigation; the shell only presents their current control.
export function useRouteSearch({ label, placeholder, value, onChange, onSubmit, onReset, disabled, schemaScope, suggestionHidden }: SearchControl) {
  const register = useContext(SearchRegistrationContext);
  const { key: routeKey } = useLocation();
  useLayoutEffect(() => {
    const registration = { routeKey, label, placeholder, value, onChange, onSubmit, onReset, disabled, schemaScope, suggestionHidden };
    register?.(registration);
    return () => register?.((current) => current === registration ? null : current);
  }, [register, routeKey, label, placeholder, value, onChange, onSubmit, onReset, disabled, schemaScope, suggestionHidden]);
}

export function TopbarSearch() {
  const registration = useContext(SearchContext);
  const location = useLocation();
  const popover = useTopbarPopover();
  // Do not expose the previous page's handler during lazy loading or route errors.
  const search = registration?.routeKey === location.key ? registration : null;
  const disabled = !search || Boolean(search.disabled);
  const label = search?.label ?? 'Search unavailable on this page';
  const input = useSearchInput({ scope: search?.schemaScope, owner: location.key,
    hidden: search?.suggestionHidden ?? 'hide', value: search?.value ?? '', disabled,
    open: popover.activeId === 'search', onOpen: () => popover.show('search'),
    onClose: () => popover.hide('search'), onChange: (value) => search?.onChange(value) });
  return <form className="search-form topbar-search" role="search" aria-label={label}
    ref={input.rootRef}
    {...input.rootProps}
    title={disabled ? label : undefined} onSubmit={(event) => {
      event.preventDefault();
      if (input.isComposing()) return;
      input.close();
      if (!disabled) search?.onSubmit(event);
    }}>
    <div className="search-input-anchor">
      <label className="search-field">
        <span className="visually-hidden">{label}</span>
        <input type="search" disabled={disabled}
          {...input.inputProps}
          placeholder={search?.placeholder ?? label} autoComplete="off" />
      </label>
      {input.visible && <SearchSuggestions listId={input.listId} activeIndex={input.activeIndex}
        choose={input.choose} {...input.assistance} />}
    </div>
    {search?.onReset && <button className="ghost icon-button" type="button" disabled={disabled}
      title="Reset search" aria-label="Reset search" onClick={search.onReset}>
      <i className="fas fa-xmark" aria-hidden="true" />
    </button>}
    <button className="icon-button" type="submit" disabled={disabled} title={label} aria-label={label}>
      <i className="fas fa-magnifying-glass" aria-hidden="true" />
    </button>
  </form>;
}
