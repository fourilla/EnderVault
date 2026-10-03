import { useLayoutEffect, useRef } from 'react';
import type { SearchSuggestion } from './types';
import './search-assistance.css';

interface SearchSuggestionsProps {
  listId: string;
  activeIndex: number;
  options: SearchSuggestion[];
  hint: string | null;
  loading: boolean;
  unavailable: boolean;
  choose: (index: number) => void;
}

export function SearchSuggestions({ listId, activeIndex, options, hint, loading, unavailable, choose }: SearchSuggestionsProps) {
  const list = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const root = list.current;
    const item = root?.children[activeIndex] as HTMLElement | undefined;
    if (!root || !item) return;
    if (item.offsetTop < root.scrollTop) root.scrollTop = item.offsetTop;
    else if (item.offsetTop + item.offsetHeight > root.scrollTop + root.clientHeight) {
      root.scrollTop = item.offsetTop + item.offsetHeight - root.clientHeight;
    }
  }, [activeIndex]);
  return <div className="search-assistance">
    <div ref={list} id={listId} role="listbox" aria-label="Search suggestions" className="search-suggestion-list">
      {options.map((option, index) => <button key={`${option.label}-${index}`} id={`${listId}-${index}`}
        type="button" role="option" aria-selected={activeIndex === index} tabIndex={-1}
        className="search-suggestion" title={`${option.label} · ${option.detail}`}
        onPointerDown={(event) => event.preventDefault()} onClick={() => choose(index)}>
        <span>{option.label}</span><small>{option.detail}</small>
      </button>)}
    </div>
    {loading && <div className="search-assistance-message" role="status">
      <i className="fas fa-spinner fa-spin" aria-hidden="true" /><span>Loading suggestions</span>
    </div>}
    {unavailable && <p className="search-assistance-message" role="status">Suggestions unavailable. Search remains available.</p>}
    {hint && <p className="search-assistance-hint">{hint}</p>}
    <p className="search-assistance-syntax">"Phrase" · Spaces / &amp;&amp;: AND · ||: OR</p>
  </div>;
}
