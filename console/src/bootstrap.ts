import { mount } from 'svelte';
import './styles/fonts.css';
import './styles/tokens.css';
import './styles/base.css';
import App from './App.svelte';
import { APP_CONTEXT, type AppContext } from './app/context.ts';
import type { IdentitySource } from './app/identity.svelte.ts';
import { createRouter } from './app/router.svelte.ts';
import { fetchEngineInfo } from './engine/info.ts';
import { createEngineInfoStore } from './engine/info-store.svelte.ts';
import { catalogs, type MessageKey } from './i18n/catalogs.ts';
import { createI18n } from './i18n/i18n.svelte.ts';

/**
 * Makes the parts of the Console from the browser it runs in, and starts it in `target`. The
 * identity source is the one thing that is given: the Console as shipped starts with nobody signed
 * in (main.ts), and WI-34 replaces that source with the one that signs in.
 */
export function startConsole(target: HTMLElement, identity: IdentitySource) {
  const context: AppContext = {
    i18n: createI18n<MessageKey>({
      catalogs,
      languages: navigator.languages,
      storage: localStorage,
      root: document.documentElement,
    }),
    identity,
    router: createRouter(window),
    // The same origin that served the Console (ADR-015): no address is written into the build.
    engineInfo: createEngineInfoStore(() => fetchEngineInfo()),
  };

  return mount(App, { target, context: new Map([[APP_CONTEXT, context]]) });
}
