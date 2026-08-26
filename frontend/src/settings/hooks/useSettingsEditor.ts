import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { saveSettings, showError } from '../settings-api';
import type { FormValue, FormValues } from '../types';

const serialize = (values: FormValues) => JSON.stringify(values);

export function useSettingsEditor(
  initialValues: FormValues,
  endpoint: string,
  onDirtyChange: (dirty: boolean) => void,
  confirmSave?: (values: FormValues, baseline: FormValues) => Promise<boolean>,
  afterSave?: (values: FormValues) => FormValues,
  onSaved?: (values: FormValues) => void,
) {
  const [values, setValues] = useState<FormValues>(initialValues);
  const [baseline, setBaseline] = useState<FormValues>(initialValues);
  const [saving, setSaving] = useState(false);
  const valuesRef = useRef(values);
  const baselineRef = useRef(baseline);
  valuesRef.current = values;
  baselineRef.current = baseline;
  const dirty = useMemo(() => serialize(values) !== serialize(baseline), [baseline, values]);

  useEffect(() => onDirtyChange(dirty), [dirty, onDirtyChange]);
  useEffect(() => {
    const beforeUnload = (event: BeforeUnloadEvent) => {
      if (!dirty) return;
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [dirty]);

  const change = useCallback((name: string, value: FormValue) => {
    setValues((current) => ({ ...current, [name]: value }));
  }, []);

  const save = useCallback(async () => {
    if (!dirty || saving) return;
    if (confirmSave && !(await confirmSave(valuesRef.current, baselineRef.current))) return;
    setSaving(true);
    try {
      await saveSettings(endpoint, valuesRef.current);
      const savedValues = afterSave ? afterSave(valuesRef.current) : valuesRef.current;
      setValues(savedValues);
      setBaseline(savedValues);
      onSaved?.(savedValues);
    } catch (error) {
      showError(error);
    } finally {
      setSaving(false);
    }
  }, [afterSave, confirmSave, dirty, endpoint, onSaved, saving]);

  useEffect(() => {
    const keyDown = (event: KeyboardEvent) => {
      if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 's' || !dirty) return;
      event.preventDefault();
      void save();
    };
    document.addEventListener('keydown', keyDown);
    return () => document.removeEventListener('keydown', keyDown);
  }, [dirty, save]);

  return {
    values,
    baseline,
    dirty,
    saving,
    change,
    save,
    discard: () => setValues(baseline),
  };
}
