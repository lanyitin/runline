// A small, real implementation of the part of the Engine's API that the Console reads:
// `GET /api/v1/info` (no token) and `GET /api/v1/system` (a Bearer token; 08-api.md). It listens on
// a port and speaks HTTP, so what is tested against it is the Console's real request and real
// response handling. It is not the Engine: its behaviour is held to the Engine's by the contract
// tests (contract/), which run against this and, with `npm run test:contract`, against a real
// packaged Engine.
//
// It can also fail like the Engine does: a 503 while shutting down, a 500, an answer that is not
// what the contract says, an answer that does not come.

import { createServer, type IncomingMessage, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { FakeBackend, type FakeBackendOptions } from './fake-backend';

export interface FakeEngineInfo {
  version: string;
  commitHash: string;
  dirty: boolean;
}

export interface FakeCaller {
  name: string;
  role: 'developer' | 'admin';
  token: string;
}

export interface FakeSystemDetails {
  buildTime: string;
  jdk: string;
  startedAt: string;
  uptimeSeconds: number;
  allowListVersion: string;
}

export interface FakeEngineOptions extends FakeBackendOptions {
  /** Who may call `GET /api/v1/system`, by token. */
  callers?: FakeCaller[];
  system?: Partial<FakeSystemDetails>;
}

/** An answer that is given instead of the real one, a number of times: the Engine failing. */
export interface FakeFault {
  /** Matched against `METHOD /path?query`. */
  match: RegExp;
  status: number;
  /** The body; JSON when it is not a string. */
  body?: unknown;
  /** How many requests it answers; `Infinity` until it is removed. */
  times: number;
  /** Closes the connection without answering. */
  drop?: boolean;
}

const readBody = (request: IncomingMessage): Promise<Buffer> =>
  new Promise((resolve, reject) => {
    const chunks: Buffer[] = [];
    request.on('data', (chunk: Buffer) => chunks.push(chunk));
    request.on('end', () => resolve(Buffer.concat(chunks)));
    request.on('error', reject);
  });

const DEFAULT_SYSTEM: FakeSystemDetails = {
  buildTime: '2026-10-05T08:30:00Z',
  jdk: '25.0.4+1-LTS',
  startedAt: '2026-10-04T04:00:00Z',
  uptimeSeconds: 100_000,
  allowListVersion: '3',
};

export type FakeEngineMode =
  | 'normal'
  | 'shutting-down'
  | 'internal-error'
  | 'malformed-json'
  | 'wrong-shape'
  | 'hang';

export class FakeEngine {
  mode: FakeEngineMode = 'normal';
  requests = 0;
  /** The paths asked, in order, with the Authorization and X-Session-Code header each had (or null). */
  readonly log: Array<{ path: string; authorization: string | null; sessionCode: string | null }> = [];
  /** Who is known by token; a token removed from here is refused from then on (an expired session). */
  callers: FakeCaller[] = [];
  /** What the API of the pipelines keeps and does: see fake-backend.ts. */
  readonly backend: FakeBackend;
  /** Failures to give: the first fault that matches a request answers it. */
  readonly faults: FakeFault[] = [];

  private constructor(
    private readonly server: Server,
    readonly url: string,
    backend: FakeBackend,
  ) {
    this.backend = backend;
  }

  static async start(info: FakeEngineInfo, options: FakeEngineOptions = {}): Promise<FakeEngine> {
    let engine!: FakeEngine;
    const system = { ...DEFAULT_SYSTEM, ...options.system };
    const backend = new FakeBackend(options);
    const server = createServer(async (request, response) => {
      // The tests run the Console in a page of another origin than the Fake (the real Engine is
      // the same origin as the Console and has no CORS): the browser engine of the tests asks.
      response.setHeader('Access-Control-Allow-Origin', '*');
      response.setHeader('Access-Control-Allow-Headers', 'Authorization, Content-Type, X-Session-Code');
      response.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, PATCH, DELETE');
      if (request.method === 'OPTIONS') {
        // The question of the browser engine, not a request of the Console: not counted.
        response.writeHead(204);
        response.end();
        return;
      }
      engine.requests += 1;
      const url = new URL(request.url ?? '/', 'http://fake');
      const path = url.pathname;
      const sessionCode = request.headers['x-session-code'];
      engine.log.push({
        path: request.url ?? '',
        authorization: request.headers.authorization ?? null,
        sessionCode: typeof sessionCode === 'string' ? sessionCode : null,
      });
      const body = await readBody(request);

      const json = (status: number, payload: string, headers: Record<string, string> = {}) => {
        response.writeHead(status, {
          'Content-Type': 'application/json; charset=UTF-8',
          'Cache-Control': 'no-store',
          ...headers,
        });
        response.end(payload);
      };
      const fault = engine.faults.find((f) => f.match.test(`${request.method} ${request.url}`));
      if (fault) {
        fault.times -= 1;
        if (fault.times <= 0) engine.faults.splice(engine.faults.indexOf(fault), 1);
        if (fault.drop) {
          request.socket.destroy();
          return;
        }
        const payload = typeof fault.body === 'string' ? fault.body : JSON.stringify(fault.body ?? {});
        return json(fault.status, payload);
      }

      const isInfo = path === '/api/v1/info';
      const isSystem = path === '/api/v1/system';
      const isApi = path.startsWith('/api/v1/');
      if (!isApi) {
        response.writeHead(404, { 'Content-Length': 0 });
        response.end();
        return;
      }
      switch (engine.mode) {
        case 'normal': {
          if (isInfo && request.method === 'GET') return json(200, JSON.stringify(info));
          // As the Engine answers a missing or unknown token: 401, Bearer, no body. Beside the
          // Bearer token the Fake knows the header `X-Session-Code`, with the same callers: the
          // second way to sign in of the tests that show the Console does not depend on the kind of
          // credential (test-support/code-method.ts). The Engine has no such header.
          const token =
            /^Bearer (.+)$/.exec(request.headers.authorization ?? '')?.[1] ??
            (typeof sessionCode === 'string' ? sessionCode : undefined);
          const caller = engine.callers.find((c) => c.token === token);
          if (!caller) {
            if (isInfo || isSystem || engine.backend.handles(request.method ?? '', path)) {
              response.writeHead(401, {
                'WWW-Authenticate': 'Bearer realm=runline',
                'Content-Length': 0,
              });
              response.end();
              return;
            }
            response.writeHead(404, { 'Content-Length': 0 });
            response.end();
            return;
          }
          if (isSystem && request.method === 'GET') {
            return json(
              200,
              JSON.stringify({
                ...info,
                ...system,
                caller: { name: caller.name, role: caller.role },
              }),
            );
          }
          const answered = engine.backend.handle({
            method: request.method ?? 'GET',
            path,
            query: url.searchParams,
            caller: { name: caller.name, role: caller.role },
            body,
          });
          if (!answered) {
            response.writeHead(404, { 'Content-Length': 0 });
            response.end();
            return;
          }
          if (answered.json === undefined) {
            response.writeHead(answered.status, { 'Content-Length': 0, ...answered.headers });
            response.end();
            return;
          }
          return json(answered.status, JSON.stringify(answered.json), answered.headers);
        }
        case 'shutting-down':
          return json(503, JSON.stringify({ error: 'shutting_down', message: 'Shutting down' }));
        case 'internal-error':
          return json(
            500,
            JSON.stringify({ error: 'internal_error', message: 'boom', errorId: 'e-1' }),
          );
        case 'malformed-json':
          return json(200, '{"version": ');
        case 'wrong-shape':
          return json(200, JSON.stringify({ version: 7, commitHash: null }));
        case 'hang':
          return; // never answers
      }
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    const { port } = server.address() as AddressInfo;
    engine = new FakeEngine(server, `http://127.0.0.1:${port}`, backend);
    engine.callers = [...(options.callers ?? [])];
    return engine;
  }

  async stop(): Promise<void> {
    this.backend.stop();
    this.server.closeAllConnections();
    await new Promise<void>((resolve) => this.server.close(() => resolve()));
  }
}
