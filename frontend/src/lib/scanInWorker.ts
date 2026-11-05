import { scanBlob, type ScanResult } from './fileScan';

type Bounds = { minChunkSize: number; targetChunkSize: number; maxChunkSize: number };

/**
 * Chunk + hash a file off the main thread so the UI stays responsive on large files.
 * Falls back to scanning on the calling thread where Workers are unavailable.
 */
export function scanInWorker(
  file: Blob,
  bounds: Bounds,
  onProgress?: (read: number, total: number) => void
): Promise<ScanResult> {
  if (typeof Worker === 'undefined') return scanBlob(file, bounds, onProgress);
  return new Promise<ScanResult>((resolve, reject) => {
    const worker = new Worker(new URL('../workers/scan.worker.ts', import.meta.url), { type: 'module' });
    worker.onmessage = (e: MessageEvent) => {
      const m = e.data;
      if (m.type === 'progress') onProgress?.(m.read, m.total);
      else if (m.type === 'done') { worker.terminate(); resolve(m.result); }
      else { worker.terminate(); reject(new Error(m.message)); }
    };
    worker.onerror = (e) => { worker.terminate(); reject(new Error(e.message || 'scan worker failed')); };
    worker.postMessage({ file, bounds });
  });
}
