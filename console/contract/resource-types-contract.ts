// What `GET /api/v1/resource-types` promises (08-api.md, ADR-021, WI-55), as tests that run on any
// server that claims to be the Engine: the Fake of test-support (always, in `npm test`) and a real
// packaged Engine (`npm run test:contract`). The catalog is what the resource forms of the Console
// show, so it must be what the server accepts: every entry, kind and property it tells is tried
// against the resource API. Every resource made is deleted, so that the real Engine is left as it
// was.

import { afterEach, describe, expect, test } from 'vitest';
import type { PipelinesContractSetup } from './pipelines-contract';
import type { ContractCaller } from './system-contract';

interface Answer {
  status: number;
  body: any;
}

const GROUPS = ['chat', 'completions', 'embeddings', 'models', 'responses', 'moderations', 'rerank', 'images', 'audio', 'files', 'batches'];

export function describeResourceTypesContract(name: string, setup: PipelinesContractSetup) {
  const ada = () => setup.callers().find((c) => c.role === 'developer')!;
  const root = () => setup.callers().find((c) => c.role === 'admin')!;

  async function call(who: ContractCaller | null, method: string, path: string, body?: unknown): Promise<Answer> {
    const headers: Record<string, string> = {};
    if (who) headers.Authorization = `Bearer ${who.token}`;
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const response = await fetch(`${setup.baseUrl()}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    return { status: response.status, body: text === '' ? null : JSON.parse(text) };
  }

  let counter = 0;
  const fresh = (prefix: string) => `${prefix}-${Date.now().toString(36)}${(counter++).toString(36)}`;

  const catalog = async () => {
    const answer = await call(root(), 'GET', '/api/v1/resource-types');
    expect(answer.status).toBe(200);
    return answer.body.types as Array<Record<string, any>>;
  };
  const typeNamed = async (type: string) => (await catalog()).find((t) => t.type === type)!;

  const made: string[] = [];
  const define = async (type: string, settings: unknown): Promise<Answer> => {
    const resourceName = fresh('rt');
    const answer = await call(root(), 'POST', '/api/v1/resources', { name: resourceName, capacity: 1, type, settings });
    if (answer.status === 201) made.push(resourceName);
    return answer;
  };
  const change = (resourceName: string, settings: unknown) =>
    call(root(), 'PATCH', `/api/v1/resources/${resourceName}`, { settings });
  afterEach(async () => {
    for (const resourceName of made.splice(0)) await call(root(), 'DELETE', `/api/v1/resources/${resourceName}`);
  });
  /** null for an answer that took the settings, the `problem` for one that refused them. */
  const problemOf = (answer: Answer) => {
    if (answer.status === 201 || answer.status === 200) return null;
    expect([answer.status, answer.body?.error]).toEqual([422, 'invalid_resource']);
    return answer.body.problem as string;
  };

  describe(`the resource type catalog of ${name}`, () => {
    test('an admin reads it, a developer is a 403 forbidden and nobody is a 401; reading it twice gives the same and changes nothing', async () => {
      const before = await call(root(), 'GET', '/api/v1/resources');
      const first = await call(root(), 'GET', '/api/v1/resource-types');
      const second = await call(root(), 'GET', '/api/v1/resource-types');

      expect(first.status).toBe(200);
      expect(second.body).toEqual(first.body);
      expect((await call(root(), 'GET', '/api/v1/resources')).body).toEqual(before.body);
      const asDeveloper = await call(ada(), 'GET', '/api/v1/resource-types');
      expect([asDeveloper.status, asDeveloper.body?.error]).toEqual([403, 'forbidden']);
      expect((await call(null, 'GET', '/api/v1/resource-types')).status).toBe(401);
    });

    test('it is the closed set of types in the Engine order; counter and file have nothing more', async () => {
      const types = await catalog();

      expect(types.map((t) => t.type)).toEqual(['counter', 'file', 'jdbc-pool', 'openai-compatible']);
      expect(types[0]).toEqual({ type: 'counter' });
      expect(types[1]).toEqual({ type: 'file' });
      expect(Object.keys(types[2]).sort()).toEqual(['databases', 'type']);
      expect(Object.keys(types[3]).sort()).toEqual(['endpoints', 'requestParameters', 'type']);
    });

    test('each endpoint of openai-compatible says its group, method, path, request and response, whether it streams, is enabled by default and is stateful', async () => {
      const { endpoints } = await typeNamed('openai-compatible');

      expect(endpoints.length).toBeGreaterThan(0);
      expect(new Set(endpoints.map((e: any) => e.id)).size).toBe(endpoints.length);
      for (const entry of endpoints) {
        expect(Object.keys(entry).sort()).toEqual(
          ['defaultEnabled', 'group', 'id', 'method', 'path', 'request', 'response', 'stateful', 'streams'].sort(),
        );
        expect(GROUPS).toContain(entry.group);
        expect(['GET', 'POST', 'DELETE']).toContain(entry.method);
        expect(entry.path).toMatch(/^\/[a-z_/{}]+$/);
        expect(['none', 'json', 'multipart']).toContain(entry.request);
        expect(['json', 'binary']).toContain(entry.response);
        for (const flag of ['streams', 'defaultEnabled', 'stateful']) expect(typeof entry[flag]).toBe('boolean');
        // The default posture is "only generating".
        expect(entry.stateful && entry.defaultEnabled).toBe(false);
      }
      expect(endpoints.find((e: any) => e.id === 'chat.completions')).toEqual({
        id: 'chat.completions',
        group: 'chat',
        method: 'POST',
        path: '/chat/completions',
        request: 'json',
        response: 'json',
        streams: true,
        defaultEnabled: true,
        stateful: false,
      });
      // The service keeps a response it makes unless told not to: making one changes what it keeps.
      expect(endpoints.find((e: any) => e.id === 'responses.create')).toEqual({
        id: 'responses.create',
        group: 'responses',
        method: 'POST',
        path: '/responses',
        request: 'json',
        response: 'json',
        streams: true,
        defaultEnabled: false,
        stateful: true,
      });
    });

    test('each request parameter says the kind of its value, and only a number may have a ceiling', async () => {
      const { requestParameters } = await typeNamed('openai-compatible');

      expect(requestParameters.length).toBeGreaterThan(0);
      for (const parameter of requestParameters) {
        expect(Object.keys(parameter).sort()).toEqual(['ceiling', 'kind', 'name']);
        expect(['number', 'text', 'textOrList', 'object']).toContain(parameter.kind);
        expect(parameter.ceiling).toBe(parameter.kind === 'number');
      }
      expect(requestParameters.map((p: any) => p.name)).toContain('model');
    });

    test('each database kind of jdbc-pool says its properties, each with a rule', async () => {
      const { databases } = await typeNamed('jdbc-pool');

      expect(databases.map((d: any) => d.kind)).toContain('postgresql');
      for (const db of databases) {
        expect(Object.keys(db).sort()).toEqual(['kind', 'properties']);
        for (const property of db.properties) {
          const extra = { text: 'maxLength', oneOf: 'values', pattern: 'pattern' }[property.rule as string];
          expect(extra).toBeDefined();
          expect(Object.keys(property).sort()).toEqual(['name', 'rule', extra!].sort());
        }
      }
    });

    test('every endpoint it tells is accepted for a new and a changed resource, a name it does not tell is invalid_endpoint, and those enabled by default are what is written without endpoints', async () => {
      const { endpoints } = await typeNamed('openai-compatible');
      const told: string[] = endpoints.map((e: any) => e.id);
      const plain = await define('openai-compatible', { baseUrl: 'http://127.0.0.1:9/v1' });
      expect(plain.status).toBe(201);
      expect(plain.body.settings.endpoints).toEqual(endpoints.filter((e: any) => e.defaultEnabled).map((e: any) => e.id));

      for (const id of [...told, 'root', 'CHAT.COMPLETIONS', 'chat/completions', 'realtime']) {
        const settings = { baseUrl: 'http://127.0.0.1:9/v1', endpoints: [id] };
        const expected = told.includes(id) ? null : 'invalid_endpoint';
        expect([id, problemOf(await change(plain.body.name, settings))]).toEqual([id, expected]);
      }
      const all = await define('openai-compatible', { baseUrl: 'http://127.0.0.1:9/v1', endpoints: [...told].reverse() });
      expect(all.body.settings.endpoints).toEqual(told);
    });

    test('a request parameter it tells takes a default of its kind, a lock, and a ceiling when it says so', async () => {
      const { requestParameters } = await typeNamed('openai-compatible');
      const sample: Record<string, unknown> = { number: 0.5, text: 'x', textOrList: ['x'], object: { type: 'text' } };
      const llm = await define('openai-compatible', { baseUrl: 'http://127.0.0.1:9/v1' });

      for (const { name: parameter, kind, ceiling } of requestParameters) {
        const settings = (extra: Record<string, unknown>) => ({ baseUrl: 'http://127.0.0.1:9/v1', ...extra });
        expect([parameter, problemOf(await change(llm.body.name, settings({ defaults: { [parameter]: sample[kind] } })))]).toEqual([parameter, null]);
        expect([parameter, problemOf(await change(llm.body.name, settings({ lockedParameters: [parameter] })))]).toEqual([parameter, null]);
        expect([parameter, problemOf(await change(llm.body.name, settings({ maxValues: { [parameter]: 5 } })))]).toEqual([
          parameter,
          ceiling ? null : 'invalid_request_defaults',
        ]);
      }
      expect(problemOf(await change(llm.body.name, { baseUrl: 'http://127.0.0.1:9/v1', lockedParameters: ['messages'] }))).toBe(
        'invalid_request_defaults',
      );
    });

    test('a database kind it tells is accepted and one it does not is unsupported_database; a property it tells takes a value of its rule, another is property_not_allowed', async () => {
      const { databases } = await typeNamed('jdbc-pool');
      const settings = (kind: string, properties?: Record<string, string>) => ({
        kind,
        host: '127.0.0.1',
        database: 'orders',
        username: 'reader',
        ...(properties ? { properties } : {}),
      });
      const told: string[] = databases.map((d: any) => d.kind);
      for (const kind of ['mysql', 'PostgreSQL', 'oracle']) {
        if (!told.includes(kind)) expect([kind, problemOf(await define('jdbc-pool', settings(kind)))]).toEqual([kind, 'unsupported_database']);
      }
      for (const { kind, properties } of databases) {
        const pool = await define('jdbc-pool', settings(kind));
        expect(pool.status).toBe(201);
        for (const property of properties) {
          const good =
            property.rule === 'text' ? 'a'.repeat(property.maxLength) : property.rule === 'oneOf' ? property.values[0] : 'public';
          if (property.rule === 'pattern') expect(new RegExp(`^(?:${property.pattern})$`).test(good)).toBe(true);
          expect([property.name, problemOf(await change(pool.body.name, settings(kind, { [property.name]: good })))]).toEqual([
            property.name,
            null,
          ]);
          const bad = property.rule === 'text' ? 'a'.repeat(property.maxLength + 1) : property.rule === 'oneOf' ? 'not-one-of-them' : '1 2';
          expect([property.name, problemOf(await change(pool.body.name, settings(kind, { [property.name]: bad })))]).toEqual([
            property.name,
            'invalid_settings',
          ]);
        }
        const names = properties.map((p: any) => p.name);
        for (const other of ['sslmode', 'password', 'applicationname', 'readOnly']) {
          if (names.includes(other)) continue;
          expect([other, problemOf(await change(pool.body.name, settings(kind, { [other]: 'x' })))]).toEqual([other, 'property_not_allowed']);
        }
      }
    });
  });
}
