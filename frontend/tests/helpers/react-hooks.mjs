import assert from 'node:assert/strict';
import * as jsx from 'react/jsx-runtime';

export function createHookHarness() {
  const slots = [], pending = [];
  let cursor = 0, dirty = true;
  const same = (left, right) => left && right && left.length === right.length && left.every((value, index) => Object.is(value, right[index]));
  const react = {
    Fragment: jsx.Fragment,
    useRef(value) { const index = cursor++; return slots[index] ||= { current: value }; },
    useState(initial) {
      const index = cursor++;
      if (!slots[index]) slots[index] = { value: typeof initial === 'function' ? initial() : initial };
      return [slots[index].value, update => {
        const next = typeof update === 'function' ? update(slots[index].value) : update;
        if (!Object.is(next, slots[index].value)) { slots[index].value = next; dirty = true; }
      }];
    },
    useMemo(create, deps) {
      const index = cursor++;
      if (!slots[index] || !same(slots[index].deps, deps)) slots[index] = { deps, value: create() };
      return slots[index].value;
    },
    useCallback(callback, deps) { return react.useMemo(() => callback, deps); },
    useEffect(run, deps) {
      const index = cursor++, previous = slots[index];
      if (!previous || !same(previous.deps, deps)) {
        const next = { deps, cleanup: previous?.cleanup };
        slots[index] = next;
        pending.push(() => { next.cleanup?.(); next.cleanup = run(); });
      }
    },
  };
  return { react,
    render(run, commit = () => {}) {
      let result, count = 0;
      do {
        assert.ok(++count < 20, 'render/effect loop');
        dirty = false; cursor = 0; result = run(); commit(result); pending.splice(0).forEach(effect => effect());
      } while (dirty);
      return result;
    },
    dispose() { slots.forEach(slot => slot.cleanup?.()); },
  };
}
