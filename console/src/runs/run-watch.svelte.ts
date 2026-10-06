// The Console's way of following a run (ADR-017): it asks the Engine, with `GET /runs/{id}` and
// `GET /runs/{id}/log?after=`, and does not use the WebSocket. The rules:
//
// - Only while the run has not ended and the page is shown: a hidden page asks nothing, and when it
//   is shown again it goes on from the cursor at once. A run that has ended is read to the end of its
//   log, once, and then left alone.
// - The state of the run is read first, then the log: a run that is seen ended has written all of its
//   log already, so reading the log to the end after that leaves nothing behind.
// - A full page means there is more: the next one is read at once, without waiting.
// - A failure is retried after a longer wait each time (1, 2, 4, ... at most 30 seconds). A 401 ends
//   the session and the poll with it; a 404 says the run (or its log) is gone: it is not retried.
// - The sequence numbers set the log right (log-merge.ts); a gap is asked for once more before it is
//   shown as lines that are missing.
//
// The clock and the visibility of the page are given, so that a test says when a second has passed
// and whether anyone is looking, and the poll can be watched without sleeping.

import { untrack } from 'svelte';
import type { EngineApi } from '../api/engine-api.ts';
import { ApiFailure } from '../api/failure.ts';
import type { LogEntry, Run } from '../api/model.ts';
import { mergeLog } from './log-merge.ts';

export interface Clock {
  /** Runs [action] after [ms]; the returned function cancels it. */
  after(ms: number, action: () => void): () => void;
}

export interface Visibility {
  readonly visible: boolean;
  /** Told when the page is shown or hidden; the returned function stops it. */
  subscribe(listener: () => void): () => void;
}

export const browserClock: Clock = {
  after(ms, action) {
    const timer = setTimeout(action, ms);
    return () => clearTimeout(timer);
  },
};

export const pageVisibility: Visibility = {
  get visible() {
    return document.visibilityState === 'visible';
  },
  subscribe(listener) {
    document.addEventListener('visibilitychange', listener);
    return () => document.removeEventListener('visibilitychange', listener);
  },
};

/** The states from which a run does not move on (08-api.md). Anything else, even a state this Console does not know, may. */
export const TERMINAL_STATES: readonly string[] = [
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
  'TIMED_OUT',
];
export const isTerminal = (state: string) => TERMINAL_STATES.includes(state);

export type WatchPhase =
  /** Nothing has been read yet. */
  | 'loading'
  /** The run goes on: it is read again after the interval. */
  | 'live'
  /** The page is hidden: nothing is asked until it is shown. */
  | 'paused'
  /** The last read failed: it is read again after a wait that grows. */
  | 'retrying'
  /** The run has ended and its log is read to the end. */
  | 'done'
  /** 404: the run or its log is not there (cleaned up by the retention, or not the caller's to see). */
  | 'gone'
  /** 401: the session has ended; the Console shows the sign-in. */
  | 'signed-out';

export interface RunWatchOptions {
  runId: string;
  api: Pick<EngineApi, 'run' | 'log'>;
  clock: Clock;
  visibility: Visibility;
  /** How many lines a page of the log holds at most (the Engine's default is 500). */
  pageSize?: number;
  intervalMs?: number;
  maxBackoffMs?: number;
}

export function createRunWatch(options: RunWatchOptions) {
  const {
    runId,
    api,
    clock,
    visibility,
    pageSize = 500,
    intervalMs = 1000,
    maxBackoffMs = 30_000,
  } = options;

  let run = $state<Run | null>(null);
  let entries = $state.raw<LogEntry[]>([]);
  let phase = $state<WatchPhase>('loading');
  let failures = $state(0);
  let problem = $state.raw<ApiFailure | null>(null);
  let missing = $state(0);
  let retryInMs = $state<number | null>(null);

  let cursor = 0;
  let disposed = false;
  let running = false;
  let again = false;
  let gapAsked = false;
  let cancelTimer: (() => void) | null = null;
  let waiters: Array<() => void> = [];

  const clearTimer = () => {
    cancelTimer?.();
    cancelTimer = null;
  };
  const schedule = (ms: number) => {
    clearTimer();
    cancelTimer = clock.after(ms, () => {
      cancelTimer = null;
      void cycle();
    });
  };

  async function readLog() {
    for (;;) {
      const page = await api.log(runId, cursor, pageSize);
      if (disposed) return;
      const merged = mergeLog(cursor, page.entries);
      if (merged.missing > 0 && !gapAsked) {
        gapAsked = true; // once more from the same cursor, before it is called missing
        continue;
      }
      gapAsked = false;
      if (merged.fresh.length > 0) {
        entries = [...entries, ...merged.fresh];
        missing += merged.missing;
        cursor = merged.cursor;
      }
      if (page.entries.length < pageSize || merged.fresh.length === 0) return;
    }
  }

  async function cycle() {
    if (disposed) return;
    if (running) {
      again = true;
      return;
    }
    running = true;
    clearTimer();
    try {
      const read = await api.run(runId);
      if (disposed) return;
      run = read;
      await readLog();
      if (disposed) return;
      failures = 0;
      problem = null;
      if (isTerminal(read.state)) phase = 'done';
      else if (!visibility.visible) phase = 'paused';
      else {
        phase = 'live';
        schedule(intervalMs);
      }
    } catch (error) {
      if (disposed) return;
      const status = error instanceof ApiFailure ? error.status : 0;
      if (status === 404) {
        phase = 'gone';
      } else if (status === 401) {
        phase = 'signed-out';
      } else {
        failures += 1;
        problem = error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));
        if (!visibility.visible) phase = 'paused';
        else {
          phase = 'retrying';
          retryInMs = Math.min(maxBackoffMs, intervalMs * 2 ** (failures - 1));
          schedule(retryInMs);
        }
      }
    } finally {
      running = false;
      if (again && !disposed) {
        again = false;
        void cycle();
      } else {
        const done = waiters;
        waiters = [];
        for (const resolve of done) resolve();
      }
    }
  }

  const unsubscribe = visibility.subscribe(() => {
    if (disposed) return;
    if (visibility.visible) {
      if (phase === 'paused' && !running) void cycle();
    } else if (!running && (phase === 'live' || phase === 'retrying')) {
      clearTimer();
      phase = 'paused';
    }
  });

  return {
    get run() {
      return run;
    },
    /** What has been read of the log: in order, each sequence number once. */
    get entries() {
      return entries;
    },
    get phase() {
      return phase;
    },
    /** Reads in a row that failed. */
    get failures() {
      return failures;
    },
    /** The last failure, until a read succeeds. */
    get problem() {
      return problem;
    },
    /** The wait before the read that follows a failure (null when there is none to wait for). */
    get retryInMs() {
      return phase === 'retrying' ? retryInMs : null;
    },
    /** How many lines the log is missing (numbers skipped that the Engine did not fill). */
    get missing() {
      return missing;
    },
    /** Starts to read; ends when the first read is over. A hidden page waits until it is shown. */
    start(): Promise<void> {
      untrack(() => {
        if (visibility.visible) void cycle();
        else phase = 'paused';
      });
      return this.settled();
    },
    /** Reads now, whatever the clock says (after the page changed the run, for one). */
    refresh() {
      if (!disposed) void cycle();
    },
    /** Ends when nothing is being read. */
    settled(): Promise<void> {
      return running ? new Promise<void>((resolve) => waiters.push(resolve)) : Promise.resolve();
    },
    dispose() {
      disposed = true;
      clearTimer();
      unsubscribe();
      const done = waiters;
      waiters = [];
      for (const resolve of done) resolve();
    },
  };
}

export type RunWatch = ReturnType<typeof createRunWatch>;
