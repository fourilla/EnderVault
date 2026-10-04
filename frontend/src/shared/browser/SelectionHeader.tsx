import { useEffect, useRef } from 'react';

export function SelectionHeader({ total, selected, onChange, label, disabled = false }: {
  total: number;
  selected: number;
  onChange: (checked: boolean) => void;
  label: string;
  disabled?: boolean;
}) {
  const input = useRef<HTMLInputElement>(null);
  const allSelected = total > 0 && selected === total;
  useEffect(() => {
    if (input.current) input.current.indeterminate = selected > 0 && !allSelected;
  }, [allSelected, selected]);

  return <th className="select-col">
    <label className="select-all-label" title={label}>
      <input ref={input} className="select-all-checkbox" type="checkbox" checked={allSelected}
        disabled={disabled || total === 0} onChange={(event) => onChange(event.currentTarget.checked)}
        aria-label={label} />
    </label>
  </th>;
}
