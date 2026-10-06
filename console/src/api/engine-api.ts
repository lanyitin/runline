// The Engine's API of the pipelines as the pages use it (08-api.md): what to ask and what comes
// back, with the answers read by model.ts. Every call goes through the session (the one way out
// to the Engine, which adds the credential). An answer that is not a success is an `ApiFailure`
// with the status and the body of the Engine, which `describeApiError` turns into words; an answer
// that is no answer is an `ApiFailure` with status 0.

import { ApiFailure } from './failure.ts';
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

/** The parts of the session the API needs. */
export interface Transport {
  request(path: string, init?: RequestInit): Promise<Response>;
  upload(
    path: string,
    body: Blob,
    options?: {
      contentType?: string;
      signal?: AbortSignal;
      onProgress?: (sent: number, total: number) => void;
    },
  ): Promise<Response>;
}

export interface CreateRunRequest {
  contentHash: string;
  pipeline: string;
  parameters?: Record<string, string>;
}

export interface UploadOptions {
  signal?: AbortSignal;
  onProgress?: (sent: number, total: number) => void;
}

async function read(response: Response): Promise<unknown> {
  let text: string;
  try {
    text = await response.text();
  } catch (error) {
    throw new ApiFailure(0, null, `the answer of the Engine could not be read: ${String(error)}`);
  }
  if (text === '') return null;
  try {
    return JSON.parse(text);
  } catch {
    return response.ok ? undefined : null;
  }
}

/** The body of a success, or the failure the Engine answered. */
async function succeed(response: Response): Promise<unknown> {
  const body = await read(response);
  if (!response.ok) throw new ApiFailure(response.status, body);
  if (body === undefined) throw new ApiFailure(0, null, 'an answer of the Engine is not JSON');
  return body;
}

async function send(transport: Transport, path: string, init?: RequestInit): Promise<Response> {
  try {
    return await transport.request(path, init);
  } catch (error) {
    throw error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));
  }
}

export function createEngineApi(transport: Transport) {
  const json = (path: string, init: RequestInit = {}) =>
    send(transport, path, {
      ...init,
      headers: { Accept: 'application/json', ...init.headers },
      cache: 'no-store',
    });

  return {
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
