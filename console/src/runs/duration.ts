/** How long a run took (or has taken so far, if it is still going on), in milliseconds; null if it has not started. */
export function runDurationMs(
  run: { startedAt: string | null; finishedAt: string | null },
  now: number,
): number | null {
  if (run.startedAt === null) return null;
  const start = Date.parse(run.startedAt);
  const end = run.finishedAt === null ? now : Date.parse(run.finishedAt);
  if (Number.isNaN(start) || Number.isNaN(end)) return null;
  return Math.max(0, end - start);
}
