// The Engine's identity as the screens get it: the public `GET /api/v1/info` until someone has
// signed in, then `GET /api/v1/system` (through the session, which gives the credential) with the
// details; and the public one again after signing out. Each read tells the connection how it went.

import type { ConnectionMonitor } from '../api/connection.svelte.ts';
import type { Session } from '../auth/session.svelte.ts';
import { EngineInfoError, fetchEngineInfo, type EngineInfo } from './info.ts';
import { createEngineInfoStore } from './info-store.svelte.ts';
import { fetchSystem, toEngineInfo } from './system.ts';

export interface EngineInfoSourceOptions {
  session: Pick<Session, 'state' | 'request'>;
  connection: ConnectionMonitor;
  /** The origin of the Engine; empty means the origin that served the Console. */
  baseUrl?: string;
}

export function createEngineInfo(options: EngineInfoSourceOptions) {
  const { session, connection, baseUrl = '' } = options;

  // The system read goes through the session, which tells the connection by itself.
  const load = async (): Promise<EngineInfo> => {
    if (session.state.status === 'authenticated') {
      return toEngineInfo(await fetchSystem(session.request), Date.now());
    }
    try {
      const info = await fetchEngineInfo({ baseUrl });
      connection.record(true);
      return info;
    } catch (error) {
      const answered = error instanceof EngineInfoError && error.status !== null && error.status < 500;
      connection.record(answered);
      throw error;
    }
  };

  return createEngineInfoStore(load, {
    reloadWhen: () => (session.state.status === 'authenticated' ? session.state.identity : null),
  });
}
