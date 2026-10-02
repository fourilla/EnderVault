export function styleDeclaration(initial = {}) {
  const values = new Map(Object.entries(initial).map(([name, value]) => [name, { value, priority: '' }]));
  return {
    getPropertyValue: (name) => values.get(name)?.value || '',
    getPropertyPriority: (name) => values.get(name)?.priority || '',
    setProperty(name, value, priority = '') {
      const names = name === 'overflow' ? ['overflow-x', 'overflow-y'] : [name];
      names.forEach((property) => values.set(property, { value, priority }));
    },
    removeProperty(name) {
      const original = values.get(name)?.value || '';
      const names = name === 'overflow' ? ['overflow-x', 'overflow-y'] : [name];
      names.forEach((property) => values.delete(property));
      return original;
    },
  };
}
