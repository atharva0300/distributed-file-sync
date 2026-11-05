import { sha256 } from '@noble/hashes/sha2.js';
import { bytesToHex } from '@noble/hashes/utils.js';
import { CdcChunker } from './cdc';

export interface ScannedChunk {
  chunkIndex: number;
  offset: number;
  chunkSize: number;
  chunkHash: string;
}

export interface ScanResult {
  fileHash: string;
  size: number;
  chunks: ScannedChunk[];
}

/**
 * Single streaming pass over a Blob: computes content-defined chunk boundaries, each chunk's
 * SHA-256 and the whole-file SHA-256 without ever holding the file in memory (peak memory is
 * one stream read plus one in-flight chunk's hasher state). Uses an incremental SHA-256
 * because WebCrypto's digest() has no streaming mode.
 */
export async function scanBlob(
  blob: Blob,
  bounds: { minChunkSize: number; targetChunkSize: number; maxChunkSize: number },
  onProgress?: (bytesRead: number, total: number) => void
): Promise<ScanResult> {
  const chunker = new CdcChunker(bounds.minChunkSize, bounds.targetChunkSize, bounds.maxChunkSize);
  const fileHasher = sha256.create();
  let chunkHasher = sha256.create();
  const chunks: ScannedChunk[] = [];
  let read = 0;

  const closeChunk = (b: { index: number; offset: number; size: number }) => {
    chunks.push({
      chunkIndex: b.index,
      offset: b.offset,
      chunkSize: b.size,
      chunkHash: bytesToHex(chunkHasher.digest()),
    });
    chunkHasher = sha256.create();
  };

  const reader = blob.stream().getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    fileHasher.update(value);
    // Feed each chunk hasher exactly its own bytes: boundaries carry absolute offsets.
    let pos = 0;
    for (const b of chunker.push(value)) {
      const end = b.offset + b.size - read; // position within this piece
      chunkHasher.update(value.subarray(pos, end));
      pos = end;
      closeChunk(b);
    }
    if (pos < value.length) chunkHasher.update(value.subarray(pos));
    read += value.length;
    onProgress?.(read, blob.size);
  }
  for (const b of chunker.finish()) closeChunk(b);

  return { fileHash: bytesToHex(fileHasher.digest()), size: read, chunks };
}
