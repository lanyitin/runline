import type { EngineInfo } from './info';

export type EngineInfoState =
  | { status: 'loading' }
  | { status: 'ready'; info: EngineInfo }
  | { status: 'failed'; error: unknown };

/**
 * The Engine's identity for the screens: reads it when created and again on [reload]. Only the
 * newest try counts: an answer that arrives late for an older one is dropped.
 */
export function createEngineInfoStore(load: () => Promise<EngineInfo>) {
  let state = $state<EngineInfoState>({ status: 'loading' });
  let latest = 0;

  const start = () => {
    const attempt = ++latest;
    state = { status: 'loading' };
    load().then(
      (info) => {
        if (attempt === latest) state = { status: 'ready', info };
      },
      (error: unknown) => {
        if (attempt === latest) state = { status: 'failed', error };
      },
    );
  };
  start();

  return {
    get state(): EngineInfoState {
      return state;
    },
    reload: start,
  };
}

export type EngineInfoStore = ReturnType<typeof createEngineInfoStore>;
