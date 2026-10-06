// Whether the Engine can be reached, as the last call to it says: no probe of its own (WI-34). The
// Engine chip's status dot reads it.

export type ConnectionState = 'unknown' | 'up' | 'down';

export function createConnectionMonitor() {
  let state = $state<ConnectionState>('unknown');
  return {
    get state(): ConnectionState {
      return state;
    },
    /** The last call got an answer from the Engine (true), or got none or a server failure (false). */
    record(ok: boolean) {
      state = ok ? 'up' : 'down';
    },
  };
}

export type ConnectionMonitor = ReturnType<typeof createConnectionMonitor>;
