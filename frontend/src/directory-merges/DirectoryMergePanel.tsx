import { useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { DirectoryMergeDialog } from './DirectoryMergeDialog';
import { mergeGet, type MergeSummary } from './merge-api';

export function DirectoryMergePanel() {
  const location = useLocation();
  const [reviews, setReviews] = useState<MergeSummary[]>([]);
  const [selected, select] = useState('');
  const [error, setError] = useState('');
  const [refresh, reload] = useState(0);
  useEffect(() => {
    const id = location.hash.startsWith('#merge-') ? location.hash.slice(7) : '';
    if (id) select(id);
  }, [location.hash, location.key]);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    const load = async () => {
      try {
        const result = await mergeGet<MergeSummary[]>('/unresolved', controller.signal);
        if (!controller.signal.aborted) { setReviews(result); setError(''); }
      } catch (reason) {
        if (!controller.signal.aborted) setError(reason instanceof Error ? reason.message : 'Merge reviews unavailable.');
      } finally { if (!controller.signal.aborted) timer = setTimeout(() => void load(), 5000); }
    };
    void load();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [refresh]);
  return <>
    {error && <p role="alert">{error}</p>}
    {reviews.length > 0 && <section className="dashboard-panel">
      <header className="section-heading"><h2>Directory Merges</h2></header>
      <div className="table-wrap compact-table"><table>
        <thead><tr><th>Destination</th><th>Operation</th><th>Status</th><th>Actions</th></tr></thead>
        <tbody>{reviews.map((review) => <tr key={review.id}>
          <td><span className="table-primary-text" title={review.destinationPath}>{review.destinationPath}</span></td>
          <td>{review.operation}</td><td>{review.run?.paused ? 'Paused' : review.run?.phase?.replaceAll('_', ' ') || 'Awaiting review'}</td>
          <td><div className="table-actions"><button type="button" className="ghost icon-button action-icon"
            title="Review merge" aria-label="Review merge" onClick={() => select(review.id)}><i className="fas fa-list-check" aria-hidden="true" /></button></div></td>
        </tr>)}</tbody>
      </table></div>
    </section>}
    {selected && <DirectoryMergeDialog key={selected} id={selected} close={() => select('')} changed={() => {
      reload((value) => value + 1);
      window.dispatchEvent(new CustomEvent('endervault:notifications-changed'));
    }} />}
  </>;
}
