import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { CdcChunker, computeCdcBoundaries } from './cdc';
import { scanBlob } from './fileScan';

interface GoldenCase {
  input: 'xorshift32' | 'zeros';
  length: number;
  minSize: number;
  targetSize: number;
  maxSize: number;
  chunks: { offset: number; size: number; sha256: string }[];
}

const golden: GoldenCase[] = JSON.parse(
  readFileSync(new URL('../../../testdata/cdc-golden.json', import.meta.url), 'utf8')
).cases;

/** Must match CdcGoldenVectorTest.input() in the Java tests. */
function input(kind: string, length: number): Uint8Array {
  const out = new Uint8Array(length);
  if (kind === 'zeros') return out;
  let x = 123456789 | 0;
  for (let i = 0; i < length; i++) {
    x ^= x << 13;
    x ^= x >>> 17;
    x ^= x << 5;
    out[i] = x & 0xff;
  }
  return out;
}

describe('CDC matches the Java implementation (shared golden vectors)', () => {
  golden.forEach((c, i) => {
    const data = input(c.input, c.length);

    it(`case ${i}: boundaries and chunk hashes`, () => {
      const got = computeCdcBoundaries(data, c.minSize, c.targetSize, c.maxSize).map((b) => ({
        offset: b.offset,
        size: b.size,
        sha256: createHash('sha256').update(data.subarray(b.offset, b.offset + b.size)).digest('hex'),
      }));
      expect(got).toEqual(c.chunks);
    });

    it(`case ${i}: streaming scan equals golden for any read-piece size`, async () => {
      const bounds = { minChunkSize: c.minSize, targetChunkSize: c.targetSize, maxChunkSize: c.maxSize };
      const res = await scanBlob(new Blob([data as BlobPart]), bounds);
      expect(res.chunks.map((k) => ({ offset: k.offset, size: k.chunkSize, sha256: k.chunkHash }))).toEqual(c.chunks);
      expect(res.fileHash).toBe(createHash('sha256').update(data).digest('hex'));
    });

    it(`case ${i}: arbitrary push splits do not change boundaries`, () => {
      const chunker = new CdcChunker(c.minSize, c.targetSize, c.maxSize);
      const got = [];
      for (let p = 0, step = 7; p < data.length; p += step, step = (step * 3) % 997 + 1) {
        got.push(...chunker.push(data.subarray(p, Math.min(data.length, p + step))));
      }
      got.push(...chunker.finish());
      expect(got.map((b) => ({ offset: b.offset, size: b.size }))).toEqual(
        c.chunks.map((k) => ({ offset: k.offset, size: k.size }))
      );
    });
  });

  it('empty input yields no chunks', () => {
    expect(computeCdcBoundaries(new Uint8Array(0), 1, 16, 64)).toEqual([]);
  });

  it('rejects non power-of-two target', () => {
    expect(() => new CdcChunker(1, 100, 200)).toThrow();
  });
});
