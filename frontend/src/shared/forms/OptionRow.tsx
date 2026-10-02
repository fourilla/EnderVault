import { useId } from 'react';

export function OptionRow({ label, description, checked, disabled = false, onChange, id, name, icon,
  control = 'checkbox', className = '' }: {
  label: string;
  description?: string;
  checked: boolean;
  disabled?: boolean;
  onChange: (checked: boolean) => void;
  id?: string;
  name?: string;
  icon?: string;
  className?: string;
  control?: 'checkbox' | 'switch';
}) {
  const descriptionId = useId();
  return <label id={id} className={`option-row ${className}${disabled ? ' is-disabled' : ''}`}>
    <span className="option-copy">
      <strong>{icon && <i className={icon} aria-hidden="true" />}{label}</strong>
      {description && <small id={descriptionId}>{description}</small>}
    </span>
    <input className={`option-input option-input-${control}`} type="checkbox"
      role={control === 'switch' ? 'switch' : undefined} name={name}
      checked={checked} disabled={disabled} aria-describedby={description ? descriptionId : undefined}
      onChange={(event) => onChange(event.currentTarget.checked)} />
  </label>;
}
