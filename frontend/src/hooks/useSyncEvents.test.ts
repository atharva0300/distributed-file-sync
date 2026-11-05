import { describe, expect, it } from 'vitest';
import { createSseParser } from './useSyncEvents';

describe('createSseParser', () => {
  it('parses events split across arbitrary chunk boundaries', () => {
    const got: [string, string][] = [];
    const feed = createSseParser((e, d) => got.push([e, d]));
    const stream = ': keep-alive\n\nevent: sync\ndata: {"a":1}\n\nevent: sync\r\ndata: x\r\ndata: y\r\n\r\n';
    for (let i = 0; i < stream.length; i += 3) feed(stream.slice(i, i + 3));
    expect(got).toEqual([['sync', '{"a":1}'], ['sync', 'x\ny']]);
  });

  it('defaults the event name to message', () => {
    const got: [string, string][] = [];
    createSseParser((e, d) => got.push([e, d]))('data: hi\n\n');
    expect(got).toEqual([['message', 'hi']]);
  });
});
