// The Engine's API of the pipelines as the pages use it (08-api.md): what to ask and what comes
// back, with the answers read by model.ts. Every call goes through the session (the one way out
// to the Engine, which adds the credential). An answer that is not a success is an `ApiFailure`
// with the status and the body of the Engine, which `describeApiError` turns into words; an answer
// that is no answer is an `ApiFailure` with status 0.

import { ApiFailure } from './failure.ts';
import { jsonCall, send, succeed, type Transport } from './call.ts';
import { createAdminApi } from './admin-api.ts';
import {
  parseArtifact,
  parseCancellation,
  parseDefinitions,
  parseLog,
  parseRun,
  parseRuns,
  type Artifact,
  type Cancellation,
  type Definition,
  type LogPage,
  type Run,
} from './model.ts';

export interface CreateRunRequest {
  contentHash: string;
  pipeline: string;
  parameters?: Record<string, string>;
}

export interface UploadOptions {
  signal?: AbortSignal;
  onProgress?: (sent: number, total: number) => void;
}

export function createEngineApi(transport: Transport) {
  const json = jsonCall(transport);

  return {
    ...createAdminApi(transport),

    /** Puts a jar on the Engine: a new version (201), or the one that has these bytes already (200). */
    async uploadJar(
      file: Blob,
      options: UploadOptions = {},
    ): Promise<{ created: boolean; artifact: Artifact }> {
      let response: Response;
      try {
        response = await transport.upload('/api/v1/artifacts', file, {
          contentType: 'application/octet-stream',
          ...options,
        });
      } catch (error) {
        if (
          error instanceof ApiFailure ||
          (error instanceof DOMException && error.name === 'AbortError')
        ) {
          throw error;
        }
        throw new ApiFailure(0, null, String(error));
      }
      const body = await succeed(response);
      return { created: response.status === 201, artifact: parseArtifact(body) };
    },

    /** One version, by its hash, with every pipeline in it. */
    async artifact(contentHash: string): Promise<Artifact> {
      return parseArtifact(
        await succeed(await json(`/api/v1/artifacts/${encodeURIComponent(contentHash)}`)),
      );
    },

    async definitions(): Promise<{ definitions: Definition[]; limitations: string }> {
      return parseDefinitions(await succeed(await json('/api/v1/definitions')));
    },

    async createRun(request: CreateRunRequest): Promise<Run> {
      const response = await json('/api/v1/runs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parameters: {}, ...request }),
      });
      return parseRun(await succeed(response));
    },

    async runs(options: { pipeline?: string; limit?: number } = {}): Promise<Run[]> {
      const query = new URLSearchParams();
      if (options.pipeline !== undefined) query.set('pipeline', options.pipeline);
      if (options.limit !== undefined) query.set('limit', String(options.limit));
      const text = query.toString();
      return parseRuns(await succeed(await json(`/api/v1/runs${text ? `?${text}` : ''}`)));
    },

    async run(runId: string): Promise<Run> {
      return parseRun(await succeed(await json(`/api/v1/runs/${encodeURIComponent(runId)}`)));
    },

    /** Asks to cancel: 200 (it had not started: cancelled) or 202 (asked to stop). */
    async cancelRun(runId: string): Promise<Cancellation> {
      const response = await json(`/api/v1/runs/${encodeURIComponent(runId)}/cancel`, {
        method: 'POST',
      });
      return parseCancellation(await succeed(response));
    },

    /** The entries of the log after the cursor [after], at most [limit]. */
    async log(runId: string, after: number, limit?: number): Promise<LogPage> {
      const query = `after=${after}${limit === undefined ? '' : `&limit=${limit}`}`;
      return parseLog(
        await succeed(await json(`/api/v1/runs/${encodeURIComponent(runId)}/log?${query}`)),
      );
    },
  };
}

export type EngineApi = ReturnType<typeof createEngineApi>;
