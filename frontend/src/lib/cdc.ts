/**
 * Content-Defined Chunking (CDC) — Rabin fingerprint rolling hash.
 *
 * This is a byte-for-byte port of the server's algorithm
 * (file-sync-service: com.filesync.chunking.ContentDefinedChunkingStrategy).
 * The polynomial, window size, and boundary condition below MUST stay identical
 * to the Java implementation, or client and server will compute different chunk
 * boundaries for the same file and dedup/resumable-upload will silently break.
 *
 * Boundary condition: a boundary is declared once the current chunk has reached
 * `minSize` and `fingerprint & (targetSize - 1) === 0` (targetSize must be a power
 * of two), or unconditionally once the chunk reaches `maxSize`.
 *
 * All fingerprint arithmetic is done with BigInt, explicitly masked to 64 bits
 * after every operation, to reproduce Java's `long` (two's-complement, mod 2^64)
 * overflow semantics exactly.
 */

const POLY = 0xbfe6b8a5bf378d83n;
const WINDOW_SIZE = 64;
const MASK64 = (1n << 64n) - 1n;
const HIGH_BIT = 1n << 63n;

/** Precomputed per-byte eviction table — same construction as the Java TABLE. */
const TABLE: readonly bigint[] = buildTable();

function buildTable(): bigint[] {
  const table = new Array<bigint>(256);
  for (let b = 0; b < 256; b++) {
    let v = BigInt(b);
    for (let i = 0; i < WINDOW_SIZE; i++) {
      if ((v & HIGH_BIT) !== 0n) {
        v = ((v << 1n) ^ POLY) & MASK64;
      } else {
        v = (v << 1n) & MASK64;
      }
    }
    table[b] = v;
  }
  return table;
}

export interface ChunkBoundary {
  index: number;
  offset: number;
  size: number;
}

/**
 * Split `bytes` into content-defined chunk boundaries. Pure/deterministic —
 * does not hash; callers compute each chunk's SHA-256 separately (crypto.subtle
 * handles that far faster than a hand-rolled JS digest would).
 */
export function computeCdcBoundaries(
  bytes: Uint8Array,
  minSize: number,
  targetSize: number,
  maxSize: number
): ChunkBoundary[] {
  if (bytes.length === 0) return [];
  if ((targetSize & (targetSize - 1)) !== 0) {
    throw new Error(`targetSize must be a power of two, got ${targetSize}`);
  }

  const mask = BigInt(targetSize - 1);
  const boundaries: ChunkBoundary[] = [];

  const window = new Uint8Array(WINDOW_SIZE);
  let windowPos = 0;
  let fingerprint = 0n;

  let chunkIndex = 0;
  let chunkStart = 0;
  let chunkLen = 0;

  for (let fileOffset = 0; fileOffset < bytes.length; fileOffset++) {
    const b = bytes[fileOffset];

    const outByte = window[windowPos];
    window[windowPos] = b;
    windowPos = (windowPos + 1) & (WINDOW_SIZE - 1);

    fingerprint = ((fingerprint << 1n) ^ BigInt(b) ^ TABLE[outByte]) & MASK64;
    chunkLen++;

    const contentBoundary = chunkLen >= minSize && (fingerprint & mask) === 0n;
    const forcedBoundary = chunkLen >= maxSize;

    if (contentBoundary || forcedBoundary) {
      boundaries.push({ index: chunkIndex++, offset: chunkStart, size: chunkLen });

      chunkStart = fileOffset + 1;
      chunkLen = 0;
      window.fill(0);
      windowPos = 0;
      fingerprint = 0n;
    }
  }

  if (chunkLen > 0) {
    boundaries.push({ index: chunkIndex, offset: chunkStart, size: chunkLen });
  }

  return boundaries;
}
