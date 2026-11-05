/// <reference lib="webworker" />
import { scanBlob } from '../lib/fileScan';

export interface ScanRequest {
  file: Blob;
  bounds: { minChunkSize: number; targetChunkSize: number; maxChunkSize: number };
}

self.onmessage = async (e: MessageEvent<ScanRequest>) => {
  try {
    const result = await scanBlob(e.data.file, e.data.bounds, (read, total) =>
      self.postMessage({ type: 'progress', read, total })
    );
    self.postMessage({ type: 'done', result });
  } catch (err) {
    self.postMessage({ type: 'error', message: err instanceof Error ? err.message : String(err) });
  }
};
