// The admin calls of the Engine's API (08-api.md: Trigger, Allow-list, Shared resources, the
// setting of unsafe execution, deleting a version), as the admin pages use them: one function for
// each call, answers read by admin-model.ts. They go through the session as every call does. A
// name that a person typed goes into the address as one piece (`encodeURIComponent`): a class name
// with a `$` in it, a trigger name with a dot. A refusal, whatever its code, is an `ApiFailure`.

import { jsonCall, succeed, type Transport } from './call.ts';
import {
  parseAllowList,
  parseAllowListChange,
  parseAllowListVersions,
  parseCheck,
  parseFirings,
  parseRelease,
  parseResource,
  parseRemovalPreview,
  parseResources,
  parseSecretReload,
  parseSecrets,
  parseTrigger,
  parseTriggers,
  parseTriggerWithSecret,
  parseUnsafeSetting,
  type AllowEntry,
  type AllowList,
  type AllowListChange,
  type AllowListVersion,
  type CheckResult,
  type Firing,
  type Release,
  type RemovalPreview,
  type Resource,
  type Secret,
  type SecretReload,
  type Trigger,
  type TriggerWithSecret,
  type UnsafeSetting,
} from './admin-model.ts';

export interface CreateTriggerRequest {
  name: string;
  kind: 'cron' | 'webhook';
  contentHash: string;
  /** Whose version of the content: an admin who can see several must say. */
  uploader?: string;
  pipeline: string;
  parameters?: Record<string, string>;
  cron?: string;
  timeZone?: string;
  enabled?: boolean;
}

/** What is given of a trigger to change: at least one. */
export interface UpdateTriggerRequest {
  /** With `uploader`: the version the binding moves to. Alone, it names a version too. */
  contentHash?: string;
  uploader?: string;
  pipeline?: string;
  parameters?: Record<string, string>;
  enabled?: boolean;
  cron?: string;
  timeZone?: string;
}

export interface AddEntryRequest {
  kind: 'package' | 'class';
  name: string;
  exactOnly?: boolean;
}

export interface ChangeOptions {
  /** Say what would change and change nothing. */
  preview?: boolean;
}

const q = encodeURIComponent;
const withPreview = (path: string, options: ChangeOptions) =>
  options.preview ? `${path}?preview=true` : path;
const uploaderQuery = (uploader: string | undefined) =>
  uploader === undefined ? '' : `?${new URLSearchParams({ uploader })}`;
const jsonBody = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
});

export function createAdminApi(transport: Transport) {
  const json = jsonCall(transport);
  const entryPath = (kind: string, name: string) => `/api/v1/allowlist/entries/${q(kind)}/${q(name)}`;

  return {
    // ---- triggers ----------------------------------------------------------------------------
    async triggers(): Promise<Trigger[]> {
      return parseTriggers(await succeed(await json('/api/v1/triggers')));
    },
    async trigger(name: string): Promise<Trigger> {
      return parseTrigger(await succeed(await json(`/api/v1/triggers/${q(name)}`)));
    },
    /** Makes a trigger; a webhook's secret is in the answer, and in no other. */
    async createTrigger(request: CreateTriggerRequest): Promise<TriggerWithSecret> {
      return parseTriggerWithSecret(
        await succeed(await json('/api/v1/triggers', jsonBody('POST', request))),
      );
    },
    async updateTrigger(name: string, change: UpdateTriggerRequest): Promise<Trigger> {
      return parseTrigger(
        await succeed(await json(`/api/v1/triggers/${q(name)}`, jsonBody('PATCH', change))),
      );
    },
    async deleteTrigger(name: string): Promise<void> {
      await succeed(await json(`/api/v1/triggers/${q(name)}`, { method: 'DELETE' }));
    },
    /** A new secret for a webhook, given in the answer and in no other; the old one stops working. */
    async rotateSecret(name: string): Promise<TriggerWithSecret> {
      return parseTriggerWithSecret(
        await succeed(await json(`/api/v1/triggers/${q(name)}/rotate-secret`, { method: 'POST' })),
      );
    },
    async firings(name: string, limit?: number): Promise<Firing[]> {
      const query = limit === undefined ? '' : `?limit=${limit}`;
      return parseFirings(await succeed(await json(`/api/v1/triggers/${q(name)}/firings${query}`)));
    },

    // ---- the allow-list ----------------------------------------------------------------------
    async allowList(): Promise<AllowList> {
      return parseAllowList(await succeed(await json('/api/v1/allowlist')));
    },
    async allowListVersions(limit?: number): Promise<AllowListVersion[]> {
      const query = limit === undefined ? '' : `?limit=${limit}`;
      return parseAllowListVersions(await succeed(await json(`/api/v1/allowlist/versions${query}`)));
    },
    async addEntry(request: AddEntryRequest, options: ChangeOptions = {}): Promise<AllowListChange> {
      return parseAllowListChange(
        await succeed(
          await json(withPreview('/api/v1/allowlist/entries', options), jsonBody('POST', request)),
        ),
      );
    },
    async modifyEntry(
      kind: AllowEntry['kind'],
      name: string,
      change: { name?: string; exactOnly?: boolean },
      options: ChangeOptions = {},
    ): Promise<AllowListChange> {
      return parseAllowListChange(
        await succeed(
          await json(withPreview(entryPath(kind, name), options), jsonBody('PATCH', change)),
        ),
      );
    },
    async removeEntry(
      kind: AllowEntry['kind'],
      name: string,
      options: ChangeOptions = {},
    ): Promise<AllowListChange> {
      return parseAllowListChange(
        await succeed(
          await json(withPreview(entryPath(kind, name), options), { method: 'DELETE' }),
        ),
      );
    },
    async recheck(options: ChangeOptions = {}): Promise<AllowListChange> {
      return parseAllowListChange(
        await succeed(
          await json(withPreview('/api/v1/allowlist/recheck', options), { method: 'POST' }),
        ),
      );
    },

    // ---- shared resources --------------------------------------------------------------------
    async resources(): Promise<Resource[]> {
      return parseResources(await succeed(await json('/api/v1/resources')));
    },
    async createResource(request: { name: string; type?: string; capacity: number }): Promise<Resource> {
      return parseResource(
        await succeed(await json('/api/v1/resources', jsonBody('POST', request))),
      );
    },
    async updateResource(
      name: string,
      change: { capacity?: number; enabled?: boolean },
    ): Promise<Resource> {
      return parseResource(
        await succeed(await json(`/api/v1/resources/${q(name)}`, jsonBody('PATCH', change))),
      );
    },
    /** Checks the entity of the resource now; the answer is its last check from then on. */
    async checkResource(name: string): Promise<CheckResult> {
      return parseCheck(
        await succeed(await json(`/api/v1/resources/${q(name)}/check`, { method: 'POST' })),
      );
    },
    /** With `preview`, what deleting it would touch, and nothing is changed; without, null once deleted. */
    async deleteResource(name: string, options: ChangeOptions = {}): Promise<RemovalPreview | null> {
      const answered = await succeed(
        await json(withPreview(`/api/v1/resources/${q(name)}`, options), { method: 'DELETE' }),
      );
      return options.preview ? parseRemovalPreview(answered) : null;
    },
    /** Makes a holder let go of the resource; its run is not stopped. */
    async releaseHolder(name: string, runId: string): Promise<Release> {
      return parseRelease(
        await succeed(
          await json(`/api/v1/resources/${q(name)}/holders/${q(runId)}/release`, { method: 'POST' }),
        ),
      );
    },

    // ---- secrets -----------------------------------------------------------------------------
    /** The aliases of the keystore; no value of any of them is ever in an answer. */
    async secrets(): Promise<Secret[]> {
      return parseSecrets(await succeed(await json('/api/v1/secrets')));
    },
    async reloadSecrets(): Promise<SecretReload> {
      return parseSecretReload(
        await succeed(await json('/api/v1/secrets/reload', { method: 'POST' })),
      );
    },

    // ---- unsafe execution and versions -------------------------------------------------------
    /** Of one version: [uploader] says whose when several uploaders have the content. */
    async setUnsafeExecution(
      contentHash: string,
      pipeline: string,
      allow: boolean,
      uploader?: string,
    ): Promise<UnsafeSetting> {
      return parseUnsafeSetting(
        await succeed(
          await json(
            `/api/v1/definitions/${q(contentHash)}/${q(pipeline)}/unsafe-execution${uploaderQuery(uploader)}`,
            jsonBody('PUT', { allow }),
          ),
        ),
      );
    },
    async deleteVersion(contentHash: string, uploader?: string): Promise<void> {
      await succeed(
        await json(`/api/v1/artifacts/${q(contentHash)}${uploaderQuery(uploader)}`, {
          method: 'DELETE',
        }),
      );
    },
  };
}

export type AdminApi = ReturnType<typeof createAdminApi>;
