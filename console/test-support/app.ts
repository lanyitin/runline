// What a component test needs to run a screen for real: the real i18n, router, identity and Engine
// info store, with the Fake Engine behind the info store (a real HTTP server, see fake-engine.ts).
// Nothing here replaces a part of the Console; the parts are the ones the application is made of.

import { mount, unmount, type Component } from 'svelte';
import { APP_CONTEXT, type AppContext } from '../src/app/context';
import { SettableIdentity, type Identity } from '../src/app/identity.svelte';
import { createRouter } from '../src/app/router.svelte';
import { createEngineInfoStore } from '../src/engine/info-store.svelte';
import { fetchEngineInfo } from '../src/engine/info';
import { catalogs, type MessageKey } from '../src/i18n/catalogs';
import { createI18n } from '../src/i18n/i18n.svelte';
import { FakeEngine, type FakeEngineMode } from './fake-engine';

export const FULL_HASH = 'a3f9c1e2d93e4f5a6b7c8d9e0f1a2b3c4d5e6f70';

export interface TestApp {
  context: AppContext;
  identity: SettableIdentity;
  engine: FakeEngine;
  mount<P extends Record<string, unknown>>(component: Component<P>, props?: P): HTMLElement;
  dispose(): Promise<void>;
}

export async function createTestApp(
  options: {
    languages?: string[];
    identity?: Identity;
    path?: string;
    engineMode?: FakeEngineMode;
    dirty?: boolean;
    commitHash?: string;
  } = {},
): Promise<TestApp> {
  localStorage.clear();
  history.replaceState(null, '', options.path ?? '/');
  const engine = await FakeEngine.start({
    version: '0.4.2',
    commitHash: options.commitHash ?? FULL_HASH,
    dirty: options.dirty ?? false,
  });
  engine.mode = options.engineMode ?? 'normal';

  const identity = new SettableIdentity(options.identity);
  const context: AppContext = {
    i18n: createI18n<MessageKey>({
      catalogs,
      languages: options.languages ?? ['en'],
      storage: localStorage,
      root: document.documentElement,
    }),
    identity,
    router: createRouter(window),
    engineInfo: createEngineInfoStore(() => fetchEngineInfo({ baseUrl: engine.url })),
  };

  const mounted: Array<ReturnType<typeof mount>> = [];
  return {
    context,
    identity,
    engine,
    mount(component, props) {
      const target = document.createElement('div');
      document.body.append(target);
      mounted.push(
        mount(component, {
          target,
          props: props as never,
          context: new Map([[APP_CONTEXT, context]]),
        }),
      );
      return target;
    },
    async dispose() {
      for (const component of mounted) await unmount(component);
      document.body.innerHTML = '';
      context.i18n.dispose();
      context.router.dispose();
      await engine.stop();
    },
  };
}
