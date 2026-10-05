import { notify, postEncodedForm } from './form-api';
import { validateListBulkResult } from '../browser/list-item-actions';

export async function resolveSelectedItems(url: string, ids: readonly string[], action: string) {
  const body = await postEncodedForm(url, { ids: [...ids], action, confirmed: true });
  const result = validateListBulkResult(body, ids);
  notify(body);
  return result;
}
