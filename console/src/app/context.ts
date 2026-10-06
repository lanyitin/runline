import { getContext, setContext } from 'svelte';
import type { EngineInfoStore } from '../engine/info-store.svelte';
import type { I18n } from '../i18n/i18n.svelte';
import type { MessageKey } from '../i18n/catalogs';
import type { IdentitySource } from './identity.svelte';
import type { Router } from './router.svelte';

/**
 * What every screen may use, and nothing more: the texts, who the user is (a name and a role, never
 * a credential: ADR-017), where in the Console the user is, and the Engine's identity. The parts are
 * given from outside, so that the screens do not know where any of them comes from.
 */
export interface AppContext {
  i18n: I18n<MessageKey>;
  identity: IdentitySource;
  router: Router;
  engineInfo: EngineInfoStore;
}

export const APP_CONTEXT = Symbol('runline.app');

export const provideAppContext = (context: AppContext) => setContext(APP_CONTEXT, context);
export const useApp = () => getContext<AppContext>(APP_CONTEXT);
