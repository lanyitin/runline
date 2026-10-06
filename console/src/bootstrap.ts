import { mount } from 'svelte';
import './styles/fonts.css';
import './styles/tokens.css';
import './styles/base.css';
import App from './App.svelte';
import { createEngineApi } from './api/engine-api.ts';
import { createConnectionMonitor } from './api/connection.svelte.ts';
import { APP_CONTEXT, type AppContext } from './app/context.ts';
import { createRouter } from './app/router.svelte.ts';
import { createSession } from './auth/session.svelte.ts';
import { createTabSync } from './auth/tab-sync.ts';
import { createTokenMethod } from './auth/token-method.ts';
import { createEngineInfo } from './engine/info-source.svelte.ts';
import { catalogs, type MessageKey } from './i18n/catalogs.ts';
import { createI18n } from './i18n/i18n.svelte.ts';

/**
 * Makes the parts of the Console from the browser it runs in, and starts it in `target`: the one
 * place that knows which way to sign in is in use (the token, for now: ADR-017), where the session
 * of the tab is kept (the storage of the tab) and how the tabs talk (a BroadcastChannel). Every
 * call goes to the origin that served the Console (ADR-015): no address is written into the build.
 */
export function startConsole(target: HTMLElement) {
  const connection = createConnectionMonitor();
  const session = createSession({
    method: createTokenMethod(),
    storage: sessionStorage,
    sync: createTabSync(),
    onOutcome: (ok) => connection.record(ok),
  });

  const context: AppContext = {
    i18n: createI18n<MessageKey>({
      catalogs,
      languages: navigator.languages,
      storage: localStorage,
      root: document.documentElement,
    }),
    session,
    router: createRouter(window),
    engineInfo: createEngineInfo({ session, connection }),
    connection,
    api: createEngineApi(session),
  };

  const app = mount(App, { target, context: new Map([[APP_CONTEXT, context]]) });
  // Finds out whether this tab, or another one, has a session; the sign-in waits for the answer.
  void session.start();
  return app;
}
