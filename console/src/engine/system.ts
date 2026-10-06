// What `GET /api/v1/system` says (ADR-016, 08-api.md): the Engine's identity and details, and who
// the caller is. It needs a credential, so it is only read through the authentication boundary.

import { ApiFailure } from '../api/failure.ts';
import type { Identity } from '../app/identity.svelte.ts';
import type { EngineInfo } from './info.ts';

export interface SystemDetails {
  /** The time of the commit the build is from (ISO-8601 UTC), not the clock of the build. */
  buildTime: string;
  jdk: string;
  startedAt: string;
  uptimeSeconds: number;
  allowListVersion: string;
}

export interface SystemInfo extends EngineInfo, SystemDetails {
  caller: Identity;
}

const bad = (what: string) => new ApiFailure(0, null, `the answer of /api/v1/system ${what}`);

/** Reads the answer of `GET /api/v1/system`; an answer that is not what 08-api.md says is a failure. */
export function parseSystem(json: unknown): SystemInfo {
  const record = typeof json === 'object' && json !== null ? (json as Record<string, unknown>) : {};
  const { version, commitHash, dirty, buildTime, jdk, startedAt, uptimeSeconds, allowListVersion } =
    record;
  const caller = record.caller as Record<string, unknown> | null | undefined;
  if (typeof version !== 'string' || typeof commitHash !== 'string' || typeof dirty !== 'boolean') {
    throw bad('does not have version, commitHash, dirty');
  }
  if (
    typeof buildTime !== 'string' ||
    typeof jdk !== 'string' ||
    typeof startedAt !== 'string' ||
    typeof uptimeSeconds !== 'number' ||
    typeof allowListVersion !== 'string'
  ) {
    throw bad('does not have buildTime, jdk, startedAt, uptimeSeconds, allowListVersion');
  }
  if (
    typeof caller !== 'object' ||
    caller === null ||
    typeof caller.name !== 'string' ||
    (caller.role !== 'developer' && caller.role !== 'admin')
  ) {
    throw bad('does not say who the caller is');
  }
  return {
    version,
    commitHash,
    dirty,
    buildTime,
    jdk,
    startedAt,
    uptimeSeconds,
    allowListVersion,
    caller: { name: caller.name, role: caller.role },
  };
}

/** The Engine's identity with the details of the system, and when they were read (`Date.now()`). */
export function toEngineInfo(system: SystemInfo, receivedAt: number): EngineInfo {
  const { version, commitHash, dirty, buildTime, jdk, startedAt, uptimeSeconds, allowListVersion } =
    system;
  return {
    version,
    commitHash,
    dirty,
    system: { buildTime, jdk, startedAt, uptimeSeconds, allowListVersion, receivedAt },
  };
}

/** Sends a request to the Engine with the credential of the session (`Session.request`). */
export type Requester = (path: string, init?: RequestInit) => Promise<Response>;

export async function fetchSystem(
  request: Requester,
  options: { timeoutMs?: number } = {},
): Promise<SystemInfo> {
  const { timeoutMs = 5_000 } = options;
  let response: Response;
  try {
    response = await request('/api/v1/system', {
      headers: { Accept: 'application/json' },
      cache: 'no-store',
      signal: AbortSignal.timeout(timeoutMs),
    });
  } catch (error) {
    throw error instanceof ApiFailure
      ? error
      : new ApiFailure(0, null, `the Engine did not answer: ${String(error)}`);
  }
  const body: unknown = await response.json().catch(() => null);
  if (!response.ok) throw new ApiFailure(response.status, body);
  return parseSystem(body);
}
