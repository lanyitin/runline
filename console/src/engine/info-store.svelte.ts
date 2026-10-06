import { untrack } from 'svelte';
import type { EngineInfo } from './info';

export type EngineInfoState =
  | { status: 'loading' }
  | { status: 'ready'; info: EngineInfo }
  | { status: 'failed'; error: unknown };

export interface EngineInfoStoreOptions {
  /**
   * Read again whenever the value this returns changes (it is watched, so it may read reactive
   * state): the Engine tells more to someone who is signed in. With it, the first read is made by
   * the watch, not at creation.
   */
  reloadWhen?: () => unknown;
}

/**
 * The Engine's identity for the screens: reads it when created and again on [reload]. Only the
 * newest try counts: an answer that arrives late for an older one is dropped. A reload keeps what
 * is shown until the new answer is there, so that the screen does not flash.
 */
export function createEngineInfoStore(
  load: () => Promise<EngineInfo>,
  options: EngineInfoStoreOptions = {},
) {
  let state = $state<EngineInfoState>({ status: 'loading' });
  let latest = 0;

  const start = () => {
    const attempt = ++latest;
    if (state.status !== 'ready') state = { status: 'loading' };
    load().then(
      (info) => {
        if (attempt === latest) state = { status: 'ready', info };
      },
      (error: unknown) => {
        if (attempt === latest) state = { status: 'failed', error };
      },
    );
  };

  let stopWatching = () => {};
  if (options.reloadWhen) {
    const reloadWhen = options.reloadWhen;
    stopWatching = $effect.root(() => {
      $effect(() => {
        reloadWhen();
        untrack(start);
      });
    });
  } else {
    start();
  }

  return {
    get state(): EngineInfoState {
      return state;
    },
    reload: start,
    dispose() {
      latest += 1;
      stopWatching();
    },
  };
}

export type EngineInfoStore = ReturnType<typeof createEngineInfoStore>;
