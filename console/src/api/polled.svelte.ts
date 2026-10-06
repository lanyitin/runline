// A list that the page keeps up to date (the runs, the overview): it is read at once, and again
// after every interval while the page is shown, never while it is hidden (ADR-017, the same rule as
// the log). What was read stays on the screen when a later read fails; the failure is said beside
// it, and the next read comes after a longer wait each time. A 401 is the end of the session.
// The user can switch the reading by itself off and read by hand.

import { untrack } from 'svelte';
import type { Clock, Visibility } from '../runs/run-watch.svelte.ts';
import { ApiFailure } from './failure.ts';

export interface PolledOptions<T> {
  load: () => Promise<T>;
  clock: Clock;
  visibility: Visibility;
  intervalMs: number;
  /** Whether it reads by itself; the user can change it. */
  auto?: boolean;
  maxBackoffMs?: number;
}

export function createPolled<T>(options: PolledOptions<T>) {
  const { load, clock, visibility, intervalMs, maxBackoffMs = 30_000 } = options;

  let data = $state.raw<T | null>(null);
  let status = $state<'loading' | 'ready' | 'failed'>('loading');
  let error = $state.raw<ApiFailure | null>(null);
  let updatedAt = $state(0);
  let auto = $state(options.auto ?? true);

  let failures = 0;
  let disposed = false;
  let ended = false;
  let running = false;
  let again = false;
  let cancelTimer: (() => void) | null = null;
  let waiters: Array<() => void> = [];

  const clearTimer = () => {
    cancelTimer?.();
    cancelTimer = null;
  };
  const schedule = (ms: number) => {
    clearTimer();
    if (!auto || !visibility.visible || ended) return;
    cancelTimer = clock.after(ms, () => {
      cancelTimer = null;
      void read();
    });
  };

  async function read() {
    if (disposed) return;
    if (running) {
      again = true;
      return;
    }
    running = true;
    clearTimer();
    try {
      const loaded = await load();
      if (disposed) return;
      data = loaded;
      status = 'ready';
      error = null;
      updatedAt = Date.now();
      failures = 0;
      schedule(intervalMs);
    } catch (caught) {
      if (disposed) return;
      const failure =
        caught instanceof ApiFailure ? caught : new ApiFailure(0, null, String(caught));
      error = failure;
      if (data === null) status = 'failed';
      if (failure.status === 401) {
        ended = true;
      } else {
        failures += 1;
        schedule(Math.min(maxBackoffMs, intervalMs === 0 ? 0 : 1000 * 2 ** (failures - 1)));
      }
    } finally {
      running = false;
      if (again && !disposed) {
        again = false;
        void read();
      } else {
        const done = waiters;
        waiters = [];
        for (const resolve of done) resolve();
      }
    }
  }

  const unsubscribe = visibility.subscribe(() => {
    if (disposed || ended) return;
    if (visibility.visible) {
      if (auto && cancelTimer === null && !running) void read();
    } else {
      clearTimer();
    }
  });

  return {
    /** What was read last; null until something was. */
    get data() {
      return data;
    },
    get status() {
      return status;
    },
    /** The failure of the last read, until a read succeeds. */
    get error() {
      return error;
    },
    /** When the data was read (`Date.now()`), 0 before. */
    get updatedAt() {
      return updatedAt;
    },
    get auto() {
      return auto;
    },
    setAuto(on: boolean) {
      auto = on;
      if (!on) clearTimer();
      else if (!running) void read();
    },
    /** Reads now. */
    reload() {
      if (!disposed) void read();
    },
    start(): Promise<void> {
      // Called from an effect of a page: what `load` reads must not make the page start it again.
      untrack(() => {
        if (visibility.visible || !auto) void read();
      });
      return this.settled();
    },
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

export type Polled<T> = ReturnType<typeof createPolled<T>>;
