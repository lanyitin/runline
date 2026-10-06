// How the lines of a log are put together as pages of it come in (ADR-017): the log only grows and
// its sequence numbers run 1, 2, 3, ... So the page that was asked for after the cursor may hold
// lines the log has already (a page asked for twice, a page that overlaps), may not be in order, and
// may skip a number. The sequence number decides, never the order in which the pages arrived.

import type { LogEntry } from '../api/model.ts';

export interface Merged {
  /** What is new, in order of the sequence number, each once. */
  fresh: LogEntry[];
  /** How many numbers are skipped, after the cursor and inside the page. */
  missing: number;
  /** The last sequence number the log now holds. */
  cursor: number;
}

export function mergeLog(cursor: number, incoming: readonly LogEntry[]): Merged {
  const bySeq = new Map<number, LogEntry>();
  for (const entry of incoming) {
    if (entry.seq > cursor && !bySeq.has(entry.seq)) bySeq.set(entry.seq, entry);
  }
  const fresh = [...bySeq.values()].sort((a, b) => a.seq - b.seq);
  let missing = 0;
  let last = cursor;
  for (const entry of fresh) {
    missing += entry.seq - last - 1;
    last = entry.seq;
  }
  return { fresh, missing, cursor: last };
}
