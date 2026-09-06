import '../../../src/main/resources/static/js/endervault-core.js';
import { createRoot } from 'react-dom/client';
import { flushSync } from 'react-dom';
import { DialogHost } from '../shared/dialogs/DialogHost';
import { resolvePage } from '../shared/browser/page-number';

const root = document.createElement('div');
document.body.append(root);
flushSync(() => createRoot(root).render(<DialogHost routeKey="standalone" />));

document.querySelectorAll<HTMLElement>('[data-page-jump]').forEach((trigger) => {
  trigger.classList.add('is-page-jump-enabled');
  trigger.setAttribute('role', 'button');
  trigger.tabIndex = 0;
  trigger.title = 'Go to page';
  trigger.setAttribute('aria-label', `${trigger.textContent?.trim()}. Go to page.`);
  const open = async () => {
    const current = Number(trigger.dataset.pageCurrent) || 1;
    const total = Number(trigger.dataset.pageTotal) || current;
    const answer = await window.EnderVault!.askTextInput({
      title: 'Go to page', label: 'Page', confirmLabel: 'Go', initialValue: String(current),
      message: `Enter a page from 1 to ${total}. Larger values open the last page.`,
    });
    if (answer === null) return;
    const url = new URL(window.location.href);
    url.searchParams.set(trigger.dataset.pageParam || 'page', String(resolvePage(answer, current, total)));
    window.EnderVault!.navigate(url.toString());
  };
  trigger.addEventListener('click', () => void open());
  trigger.addEventListener('keydown', (event) => {
    if (event.key !== 'Enter' && event.key !== ' ') return;
    event.preventDefault();
    void open();
  });
});
