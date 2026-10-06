// Time and the visibility of the page, in the hands of a test: the log poller asks them when to wake
// and whether anyone is looking, and a test says so, instead of sleeping and hoping. They are what
// the clock and the page's visibility are for the poller, not a stand-in for anything outside it.

export class ManualClock {
  private now = 0;
  private next = 1;
  private readonly timers = new Map<number, { at: number; action: () => void }>();

  after = (ms: number, action: () => void): (() => void) => {
    const id = this.next++;
    this.timers.set(id, { at: this.now + ms, action });
    return () => {
      this.timers.delete(id);
    };
  };

  /** How many timers wait. */
  get waiting(): number {
    return this.timers.size;
  }

  /** The time until the next timer is due, or null when none waits. */
  get untilNext(): number | null {
    const due = [...this.timers.values()].map((t) => t.at - this.now);
    return due.length === 0 ? null : Math.min(...due);
  }

  /** Lets [ms] pass: every timer that falls due runs, in order (also those they set that fall due). */
  advance(ms: number): void {
    const end = this.now + ms;
    for (;;) {
      const due = [...this.timers.entries()]
        .filter(([, t]) => t.at <= end)
        .sort((a, b) => a[1].at - b[1].at || a[0] - b[0])[0];
      if (!due) break;
      this.timers.delete(due[0]);
      this.now = Math.max(this.now, due[1].at);
      due[1].action();
    }
    this.now = end;
  }
}

export class ManualVisibility {
  private shown = true;
  private readonly listeners = new Set<() => void>();

  get visible(): boolean {
    return this.shown;
  }

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  set(visible: boolean): void {
    this.shown = visible;
    for (const listener of [...this.listeners]) listener();
  }
}
