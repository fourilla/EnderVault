export function mountWhenPreviewOpened(container: HTMLElement, mount: () => void): void {
  const disclosure = container.closest<HTMLDetailsElement>('details[data-shared-preview]');
  if (!disclosure || disclosure.open) {
    mount();
    return;
  }

  const handleToggle = () => {
    if (!disclosure.open) return;
    disclosure.removeEventListener('toggle', handleToggle);
    mount();
  };
  disclosure.addEventListener('toggle', handleToggle);
}
