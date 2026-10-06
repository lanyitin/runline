import { describe, expect, test } from 'vitest';
import { mergeLog } from './log-merge';

const entry = (seq: number) => ({
  seq,
  at: '2026-10-05T00:00:00Z',
  stream: 'STDOUT',
  line: `line ${seq}`,
});
const seqs = (entries: Array<{ seq: number }>) => entries.map((e) => e.seq);

describe('mergeLog', () => {
  test('takes the entries that follow the cursor, in order', () => {
    const merged = mergeLog(2, [entry(3), entry(4), entry(5)]);
    expect(seqs(merged.fresh)).toEqual([3, 4, 5]);
    expect(merged.missing).toBe(0);
    expect(merged.cursor).toBe(5);
  });

  test('leaves out what the log has already: the same seq is never shown twice', () => {
    const merged = mergeLog(3, [entry(2), entry(3), entry(4), entry(4), entry(5)]);
    expect(seqs(merged.fresh)).toEqual([4, 5]);
    expect(merged.missing).toBe(0);
  });

  test('puts what came out of order in order', () => {
    expect(seqs(mergeLog(0, [entry(2), entry(1), entry(3)]).fresh)).toEqual([1, 2, 3]);
  });

  test('says how many are missing when a number is skipped, after the cursor and inside the page', () => {
    expect(mergeLog(3, [entry(6), entry(7)]).missing).toBe(2);
    expect(mergeLog(0, [entry(1), entry(2), entry(5)]).missing).toBe(2);
    expect(mergeLog(0, [entry(3)]).missing).toBe(2);
  });

  test('a page with nothing new leaves the cursor, and nothing is missing', () => {
    const merged = mergeLog(7, []);
    expect(merged).toEqual({ fresh: [], missing: 0, cursor: 7 });
    expect(mergeLog(7, [entry(5), entry(7)]).cursor).toBe(7);
  });
});
