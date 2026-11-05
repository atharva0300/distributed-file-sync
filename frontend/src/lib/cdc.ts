/**
 * Content-Defined Chunking (CDC) — Rabin fingerprint rolling hash, streaming.
 *
 * Port of the server's algorithm (file-sync-service: ContentDefinedChunkingStrategy).
 * Polynomial, window size and boundary condition MUST stay identical to the Java
 * implementation; shared golden vectors (src/lib/__fixtures__/cdc-golden.json, also
 * read by the Java tests) fail the build if they drift.
 *
 * Boundary: once the chunk has reached `minSize`, a boundary is declared when
 * `fingerprint & (targetSize - 1) === 0`, or unconditionally at `maxSize`.
 *
 * Arithmetic: Java uses a 64-bit long. The recurrence fp = (fp << 1) ^ b ^ TABLE[out]
 * only ever moves bits upward, so the low 32 bits of fp depend solely on the low 32
 * bits of the previous fp and of TABLE entries. Because targetSize is required to be
 * <= 2^31, the mask only inspects those low bits, so plain 32-bit integer math gives
 * the identical decision to the 64-bit original, at native speed and with no BigInt.
 */

export const WINDOW_SIZE = 64;
const POLY = 0xbfe6b8a5bf378d83n;
const MASK64 = (1n << 64n) - 1n;
const HIGH_BIT = 1n << 63n;

/** Low 32 bits of the Java TABLE (built with 64-bit arithmetic, then truncated). */
const TABLE: Uint32Array = buildTable();

function buildTable(): Uint32Array {
  const table = new Uint32Array(256);
  for (let b = 0; b < 256; b++) {
    let v = BigInt(b);
    for (let i = 0; i < WINDOW_SIZE; i++) {
      v = (v & HIGH_BIT) !== 0n ? ((v << 1n) ^ POLY) & MASK64 : (v << 1n) & MASK64;
    }
    table[b] = Number(v & 0xffffffffn);
  }
  return table;
}

export interface ChunkBoundary {
  index: number;
  offset: number;
  size: number;
}

/**
 * Incremental chunker. Feed bytes in arbitrary pieces with `push`; each call returns the
 * boundaries completed by that piece. Call `finish` once to flush the trailing chunk.
 * Memory use is O(window) regardless of input size.
 */
export class CdcChunker {
  private readonly window = new Uint8Array(WINDOW_SIZE);
  private windowPos = 0;
  private fingerprint = 0;
  private chunkIndex = 0;
  private chunkStart = 0;
  private chunkLen = 0;
  private fileOffset = 0;
  private readonly mask: number;

  constructor(
    private readonly minSize: number,
    targetSize: number,
    private readonly maxSize: number
  ) {
    if (targetSize <= 0 || (targetSize & (targetSize - 1)) !== 0 || targetSize > 2 ** 31) {
      throw new Error(`targetSize must be a power of two <= 2^31, got ${targetSize}`);
    }
    this.mask = (targetSize - 1) >>> 0;
  }

  push(bytes: Uint8Array): ChunkBoundary[] {
    const out: ChunkBoundary[] = [];
    for (let i = 0; i < bytes.length; i++) {
      const b = bytes[i];
      const outByte = this.window[this.windowPos];
      this.window[this.windowPos] = b;
      this.windowPos = (this.windowPos + 1) & (WINDOW_SIZE - 1);

      this.fingerprint = ((this.fingerprint << 1) ^ b ^ TABLE[outByte]) >>> 0;
      this.chunkLen++;
      this.fileOffset++;

      const content = this.chunkLen >= this.minSize && (this.fingerprint & this.mask) === 0;
      if (content || this.chunkLen >= this.maxSize) {
        out.push({ index: this.chunkIndex++, offset: this.chunkStart, size: this.chunkLen });
        this.chunkStart = this.fileOffset;
        this.chunkLen = 0;
        this.window.fill(0);
        this.windowPos = 0;
        this.fingerprint = 0;
      }
    }
    return out;
  }

  finish(): ChunkBoundary[] {
    if (this.chunkLen === 0) return [];
    const last = { index: this.chunkIndex++, offset: this.chunkStart, size: this.chunkLen };
    this.chunkStart = this.fileOffset;
    this.chunkLen = 0;
    return [last];
  }
}

/** Convenience for in-memory input (tests, small data). */
export function computeCdcBoundaries(
  bytes: Uint8Array,
  minSize: number,
  targetSize: number,
  maxSize: number
): ChunkBoundary[] {
  const c = new CdcChunker(minSize, targetSize, maxSize);
  return [...c.push(bytes), ...c.finish()];
}
