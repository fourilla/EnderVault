export function batchSummary(items) {
    const total = items.reduce((sum, item) => sum + item.file.size, 0);
    const loaded = items.reduce((sum, item) => sum + Math.max(0, Math.min(item.file.size, item.loaded)), 0);
    const complete = items.filter(item => item.state === 'complete').length;
    return { total, loaded, complete,
        failed: items.filter(item => item.state === 'failed').length,
        canceled: items.filter(item => item.state === 'canceled').length,
        percent: total ? loaded * 100 / total : complete === items.length ? 100 : 0 };
}

export async function runBatch(items, parallel, process) {
    let cursor = 0;
    const worker = async () => {
        while (cursor < items.length) {
            const item = items[cursor++];
            if (item.state === 'queued') await process(item);
        }
    };
    await Promise.all(Array.from({ length: Math.min(Math.max(1, parallel), items.length) }, worker));
}
