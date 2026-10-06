import { describe, expect, test } from 'vitest';
import { runDurationMs } from './duration';

const run = (startedAt: string | null, finishedAt: string | null) => ({ startedAt, finishedAt });
const NOW = Date.parse('2026-10-05T10:00:10Z');

describe('how long a run took', () => {
  test('is the time from its start to its end', () => {
    expect(runDurationMs(run('2026-10-05T10:00:00Z', '2026-10-05T10:01:24Z'), NOW)).toBe(84_000);
  });

  test('of a run that is going on is the time from its start until now', () => {
    expect(runDurationMs(run('2026-10-05T10:00:00Z', null), NOW)).toBe(10_000);
  });

  test('of a run that has not started is not known', () => {
    expect(runDurationMs(run(null, null), NOW)).toBeNull();
  });

  test('of a run that ended without starting (cancelled in the queue) is not known either', () => {
    expect(runDurationMs(run(null, '2026-10-05T10:00:05Z'), NOW)).toBeNull();
  });

  test('is never negative, when the clock of the browser is behind the Engine', () => {
    expect(runDurationMs(run('2026-10-05T10:00:30Z', null), NOW)).toBe(0);
  });

  test('is not known for a time that is no time', () => {
    expect(runDurationMs(run('yesterday', null), NOW)).toBeNull();
  });
});
