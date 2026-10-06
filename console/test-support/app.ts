// What a component test needs to run a screen for real: the real i18n, router, session (with the
// token method, the tab's storage and the channel between tabs), connection and Engine info store,
// with the Fake Engine behind them (a real HTTP server, see fake-engine.ts). Nothing here replaces
// a part of the Console; the parts are the ones the application is made of. Signing in is done for
// real: the Fake Engine knows the token of the identity a test asks for.

import { mount, unmount, type Component } from 'svelte';
import { createConnectionMonitor } from '../src/api/connection.svelte';
import { APP_CONTEXT, type AppContext } from '../src/app/context';
import type { Identity } from '../src/app/identity.svelte';
import { createRouter } from '../src/app/router.svelte';
import { SESSION_STORAGE_KEY } from '../src/auth/credential-store';
import type { AuthMethod } from '../src/auth/method';
import { createSession } from '../src/auth/session.svelte';
import { createTabSync } from '../src/auth/tab-sync';
import { createTokenMethod } from '../src/auth/token-method';
import { createEngineInfo } from '../src/engine/info-source.svelte';
import { catalogs, type MessageKey } from '../src/i18n/catalogs';
import { createI18n } from '../src/i18n/i18n.svelte';
import { FakeEngine, type FakeEngineMode } from './fake-engine';

export const FULL_HASH = 'a3f9c1e2d93e4f5a6b7c8d9e0f1a2b3c4d5e6f70';
/** The token of the identity a test signs in as. */
export const TEST_TOKEN = 'tok-test-0123456789';

export interface TestApp {
  context: AppContext;
  session: AppContext['session'];
  engine: FakeEngine;
  mount<P extends Record<string, unknown>>(component: Component<P>, props?: P): HTMLElement;
  dispose(): Promise<void>;
}

export async function createTestApp(
  options: {
    languages?: string[];
    /** Signed in as this identity when the app starts (the Fake Engine knows its token). */
    identity?: Identity;
    path?: string;
    engineMode?: FakeEngineMode;
    dirty?: boolean;
    commitHash?: string;
    /** A credential the tab already holds when the app starts (a page that is reloaded). */
    storedCredential?: string;
    method?: AuthMethod;
    /** How long a tab without a session waits for another tab to give it one. */
    askTimeoutMs?: number;
  } = {},
): Promise<TestApp> {
  localStorage.clear();
  sessionStorage.clear();
  if (options.storedCredential) sessionStorage.setItem(SESSION_STORAGE_KEY, options.storedCredential);
  history.replaceState(null, '', options.path ?? '/');
  const engine = await FakeEngine.start(
    {
      version: '0.4.2',
      commitHash: options.commitHash ?? FULL_HASH,
      dirty: options.dirty ?? false,
    },
    {
      callers: options.identity ? [{ ...options.identity, token: TEST_TOKEN }] : [],
    },
  );

  // With nobody to sign in the Engine misbehaves from the start; to sign someone in it must work first.
  if (!options.identity) engine.mode = options.engineMode ?? 'normal';

  const connection = createConnectionMonitor();
  const session = createSession({
    method: options.method ?? createTokenMethod(),
    storage: sessionStorage,
    sync: createTabSync(),
    baseUrl: engine.url,
    askTimeoutMs: options.askTimeoutMs ?? 20,
    onOutcome: (ok) => connection.record(ok),
  });
  const context: AppContext = {
    i18n: createI18n<MessageKey>({
      catalogs,
      languages: options.languages ?? ['en'],
      storage: localStorage,
      root: document.documentElement,
    }),
    session,
    router: createRouter(window),
    engineInfo: createEngineInfo({ session, connection, baseUrl: engine.url }),
    connection,
  };

  await session.start();
  if (options.identity) {
    await session.signIn(TEST_TOKEN);
    engine.mode = options.engineMode ?? 'normal';
  }

  const mounted: Array<ReturnType<typeof mount>> = [];
  return {
    context,
    session,
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
      context.engineInfo.dispose();
      session.dispose();
      context.i18n.dispose();
      context.router.dispose();
      await engine.stop();
      sessionStorage.clear();
    },
  };
}
