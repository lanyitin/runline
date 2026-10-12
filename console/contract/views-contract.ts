// What the API of the pipeline views promises (08-api.md: Pipeline view, ADR-033, WI-1301), as tests
// that run on any server that claims to be the Engine: the Fake of test-support (always, in
// `npm test`) and a real packaged Engine (`npm run test:contract`). The Console's page of the data
// of a pipeline is made from these answers: the views with whether they can be read, the columns
// with their tones and drill, a page of rows with `hasMore`, and the refusals it must say in words.
//
// The pipeline is `demo-views` (dev/sample-pipelines, samples.views.ViewsPipeline): two views over
// the role `view-db`, a `jdbc-pool` that these tests bind to a SQLite file of their own. Running
// the pipeline fills the file with fixed rows, so that one set of expectations fits both servers.
// Every resource made is deleted afterwards and every version is of bytes no one has uploaded, so
// that the real Engine is left as it was and the tests can run again on it.

import { afterEach, describe, expect, test, vi } from 'vitest';
import { uniqueJar } from '../test-support/zip';
import type { PipelinesContractSetup } from './pipelines-contract';
import type { ContractCaller } from './system-contract';

interface Answer {
  status: number;
  headers: Headers;
  body: any;
}

const TERMINAL = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'TIMED_OUT'];
const ROLE = 'view-db';

export function describeViewsContract(name: string, setup: PipelinesContractSetup) {
  const developers = () => setup.callers().filter((c) => c.role === 'developer');
  const ada = () => developers()[0];
  const bob = () => developers()[1];
  const root = () => setup.callers().find((c) => c.role === 'admin')!;

  async function call(who: ContractCaller | null, method: string, path: string, body?: unknown): Promise<Answer> {
    const headers: Record<string, string> = {};
    if (who) headers.Authorization = `Bearer ${who.token}`;
    let payload: BodyInit | undefined;
    if (ArrayBuffer.isView(body)) {
      headers['Content-Type'] = 'application/octet-stream';
      payload = body as unknown as BodyInit;
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const response = await fetch(`${setup.baseUrl()}${path}`, { method, headers, body: payload });
    const text = await response.text();
    let parsed: unknown = null;
    try {
      parsed = text === '' ? null : JSON.parse(text);
    } catch {
      parsed = text;
    }
    return { status: response.status, headers: response.headers, body: parsed };
  }

  let counter = 0;
  const fresh = (prefix: string) => `${prefix}-${Date.now().toString(36)}${(counter++).toString(36)}`;

  /**
   * A version of `demo-views` made of bytes no one has uploaded, by [who]. A binding belongs to the
   * uploader and the pipeline's name, not to a version, so the one an earlier test left is taken
   * away first.
   */
  const version = async (who: ContractCaller, bytes: Uint8Array = uniqueJar(setup.jars().views, fresh('views'))) => {
    const answer = await call(who, 'POST', '/api/v1/artifacts', bytes);
    expect([200, 201]).toContain(answer.status);
    const contentHash = answer.body.contentHash as string;
    expect((await call(who, 'DELETE', rolePath(contentHash))).status).toBe(200);
    return { contentHash, bytes };
  };

  const rolePath = (contentHash: string, uploader?: string) =>
    `/api/v1/definitions/${contentHash}/demo-views/resource-bindings/${ROLE}${uploader === undefined ? '' : `?uploader=${uploader}`}`;
  const query = (params: Record<string, string>) => {
    const text = new URLSearchParams(params).toString();
    return text === '' ? '' : `?${text}`;
  };
  const list = (who: ContractCaller | null, contentHash: string, params: Record<string, string> = {}) =>
    call(who, 'GET', `/api/v1/definitions/${contentHash}/demo-views/views${query(params)}`);
  const read = (who: ContractCaller | null, contentHash: string, view: string, params: Record<string, string> = {}) =>
    call(who, 'GET', `/api/v1/definitions/${contentHash}/demo-views/views/${view}${query(params)}`);
  const bind = (who: ContractCaller, contentHash: string, resource: string, uploader?: string) =>
    call(who, 'PUT', rolePath(contentHash, uploader), { resource });

  const made: string[] = [];
  /** A resource defined by the admin, deleted when the test is over: a SQLite file of its own by default. */
  async function define(type: 'jdbc-pool' = 'jdbc-pool', options: { enabled?: boolean } = {}) {
    const resourceName = fresh('views-db');
    const body = { name: resourceName, capacity: 1, type, settings: { kind: 'sqlite', path: `${resourceName}.db` } };
    const answer = await call(root(), 'POST', '/api/v1/resources', body);
    expect(answer.status).toBe(201);
    made.push(resourceName);
    if (options.enabled === false) {
      expect((await call(root(), 'PATCH', `/api/v1/resources/${resourceName}`, { enabled: false })).status).toBe(200);
    }
    return resourceName;
  }
  afterEach(async () => {
    for (const resourceName of made.splice(0)) await call(root(), 'DELETE', `/api/v1/resources/${resourceName}`);
  });

  /** A version whose role is bound to a new database that the pipeline has run into and filled. */
  async function filled(who: ContractCaller = ada()) {
    const { contentHash } = await version(who);
    const resource = await define();
    expect((await bind(who, contentHash, resource)).status).toBe(200);
    const created = await call(who, 'POST', '/api/v1/runs', { contentHash, pipeline: 'demo-views' });
    expect(created.status).toBe(201);
    await vi.waitFor(
      async () => {
        const run = await call(who, 'GET', `/api/v1/runs/${created.body.runId}`);
        expect(TERMINAL).toContain(run.body.state);
      },
      { timeout: 30_000, interval: 100 },
    );
    expect((await call(who, 'GET', `/api/v1/runs/${created.body.runId}`)).body.state).toBe('SUCCEEDED');
    return { contentHash, resource };
  }
  const idsOf = (answer: Answer) => answer.body.rows.map((row: unknown[]) => row[0]);

  describe(`the views of a pipeline with ${name}`, () => {
    test('are listed in the order they were declared, with what each takes and shows, and without a query', async () => {
      const { contentHash } = await version(ada());
      const answer = await list(ada(), contentHash);
      expect(answer.status).toBe(200);
      expect(answer.body).toMatchObject({ contentHash, uploader: ada().name, pipeline: 'demo-views' });
      expect(answer.body.views.map((v: any) => v.id)).toEqual(['feeds', 'articles']);
      expect(answer.body.views[0]).toMatchObject({
        id: 'feeds',
        title: 'Feeds',
        description: 'The feeds and how many articles each has',
        resource: ROLE,
        parameters: [],
        columns: [
          { name: 'name', label: 'Feed', type: 'TEXT', tones: [], drill: { view: 'articles', parameter: 'feed', from: 'name' } },
          { name: 'items', label: 'Articles', type: 'INTEGER', tones: [], drill: null },
        ],
        sort: [{ column: 'name', descending: false }],
      });
      expect(answer.body.views[1]).toMatchObject({
        id: 'articles',
        title: 'Articles',
        parameters: [
          { name: 'feed', type: 'TEXT', required: false, options: [] },
          { name: 'state', type: 'CHOICE', required: false, options: ['kept', 'failed', 'skipped'] },
        ],
        sort: [{ column: 'fetched_at', descending: true }],
      });
      expect(answer.body.views[1].columns.map((c: any) => [c.name, c.type])).toEqual([
        ['id', 'INTEGER'],
        ['title', 'TEXT'],
        ['state', 'STATUS'],
        ['fetched_at', 'TIME'],
        ['feed', 'TEXT'],
      ]);
      expect(answer.body.views[1].columns[2].tones).toEqual([
        { value: 'kept', tone: 'POSITIVE' },
        { value: 'failed', tone: 'NEGATIVE' },
      ]);
      expect(JSON.stringify(answer.body)).not.toMatch(/select/i);
    });

    test('say that the role is unbound while nothing is bound and nothing is named as the role is', async () => {
      const { contentHash } = await version(ada());
      const answer = await list(ada(), contentHash);
      expect(answer.body.views.map((v: any) => v.status)).toEqual(['unbound', 'unbound']);
    });

    test('say ready once the role is bound to a database that is enabled', async () => {
      const { contentHash } = await version(ada());
      await bind(ada(), contentHash, await define());
      expect((await list(ada(), contentHash)).body.views.map((v: any) => v.status)).toEqual(['ready', 'ready']);
    });

    test('say why a role cannot be used: its resource is disabled', async () => {
      const { contentHash } = await version(ada());
      await bind(ada(), contentHash, await define('jdbc-pool', { enabled: false }));
      expect((await list(ada(), contentHash)).body.views[0].status).toBe('disabled');
    });

    test('are an empty list for a pipeline that declares none', async () => {
      const answer = await call(ada(), 'POST', '/api/v1/artifacts', uniqueJar(setup.jars().roles, fresh('none')));
      expect([200, 201]).toContain(answer.status);
      const none = await call(ada(), 'GET', `/api/v1/definitions/${answer.body.contentHash}/demo-roles/views`);
      expect([none.status, none.body.views]).toEqual([200, []]);
    });
  });

  describe(`reading a view with ${name}`, () => {
    test('is refused while the role cannot be used, with the problem and the role', async () => {
      const { contentHash } = await version(ada());
      const unbound = await read(ada(), contentHash, 'feeds');
      expect([unbound.status, unbound.body.error, unbound.body.problem, unbound.body.role]).toEqual([
        409,
        'view_resource_unavailable',
        'unbound',
        ROLE,
      ]);
      await bind(ada(), contentHash, await define('jdbc-pool', { enabled: false }));
      const disabled = await read(ada(), contentHash, 'feeds');
      expect([disabled.status, disabled.body.error, disabled.body.problem]).toEqual([409, 'view_resource_unavailable', 'disabled']);
    });

    test('is a 409 view_schema_mismatch while the database has not been made by a run', async () => {
      const { contentHash } = await version(ada());
      await bind(ada(), contentHash, await define());
      const answer = await read(ada(), contentHash, 'feeds');
      expect([answer.status, answer.body.error]).toEqual([409, 'view_schema_mismatch']);
    });

    test('gives the declared columns and a row for each, in the order of the default sort, and is never cached', async () => {
      const { contentHash } = await filled();
      const answer = await read(ada(), contentHash, 'feeds');
      expect(answer.status).toBe(200);
      expect(answer.headers.get('cache-control')).toMatch(/no-store/);
      expect(answer.body).toMatchObject({ view: 'feeds', hasMore: false, limit: 50, offset: 0 });
      expect(answer.body.columns.map((c: any) => c.name)).toEqual(['name', 'items']);
      expect(answer.body.columns[0].drill).toEqual({ view: 'articles', parameter: 'feed', from: 'name' });
      expect(answer.body.rows).toEqual([
        ['blog', 3],
        ['news', 3],
      ]);
    });

    test('gives a null, a number and a text as they are, and a text with markup as the plain text it is', async () => {
      const { contentHash } = await filled();
      const answer = await read(ada(), contentHash, 'articles');
      expect(idsOf(answer)).toEqual([7, 6, 5, 4, 3, 2, 1]);
      const last = answer.body.rows[0];
      expect(last).toEqual([7, 'Eta', 'kept', '2026-10-07T08:00:00Z', null]);
      const markup = answer.body.rows.find((row: unknown[]) => row[0] === 2);
      expect(markup[1]).toBe('Beta <b onclick="alert(1)">bold</b> & <script>alert(1)</script>');
    });

    test('pages by limit and offset: hasMore says another page follows, and no page repeats or misses a row', async () => {
      const { contentHash } = await filled();
      const first = await read(ada(), contentHash, 'articles', { limit: '3' });
      const second = await read(ada(), contentHash, 'articles', { limit: '3', offset: '3' });
      const third = await read(ada(), contentHash, 'articles', { limit: '3', offset: '6' });
      expect([first.body.hasMore, second.body.hasMore, third.body.hasMore]).toEqual([true, true, false]);
      expect([first.body.limit, second.body.offset, third.body.offset]).toEqual([3, 3, 6]);
      expect([...idsOf(first), ...idsOf(second), ...idsOf(third)]).toEqual([7, 6, 5, 4, 3, 2, 1]);
    });

    test('takes the parameters as param.<name>; a parameter that is left out does not filter', async () => {
      const { contentHash } = await filled();
      const kept = await read(ada(), contentHash, 'articles', { 'param.state': 'kept' });
      expect(idsOf(kept)).toEqual([7, 5, 3, 1]);
      const news = await read(ada(), contentHash, 'articles', { 'param.feed': 'news' });
      expect(idsOf(news)).toEqual([5, 2, 1]);
      const both = await read(ada(), contentHash, 'articles', { 'param.feed': 'news', 'param.state': 'kept' });
      expect(idsOf(both)).toEqual([5, 1]);
    });

    test('is a 400 invalid_view_parameter that names the parameter and why, and does not say the value', async () => {
      const { contentHash } = await filled();
      const notAnOption = await read(ada(), contentHash, 'articles', { 'param.state': 'secret-value' });
      expect([notAnOption.status, notAnOption.body.error, notAnOption.body.parameter, notAnOption.body.reason]).toEqual([
        400,
        'invalid_view_parameter',
        'state',
        'not_an_option',
      ]);
      expect(JSON.stringify(notAnOption.body)).not.toContain('secret-value');
      const unknown = await read(ada(), contentHash, 'articles', { 'param.nope': 'x' });
      expect([unknown.status, unknown.body.parameter, unknown.body.reason]).toEqual([400, 'nope', 'unknown']);
      const range = await read(ada(), contentHash, 'articles', { limit: '0' });
      expect([range.status, range.body.parameter, range.body.reason]).toEqual([400, 'limit', 'out_of_range']);
    });

    test('is a 404 view_not_found for a view the pipeline does not declare', async () => {
      const { contentHash } = await version(ada());
      const answer = await read(ada(), contentHash, 'nope');
      expect([answer.status, answer.body.error]).toEqual([404, 'view_not_found']);
    });

    test('is not possible without a token: 401', async () => {
      const { contentHash } = await version(ada());
      expect((await list(null, contentHash)).status).toBe(401);
      expect((await read(null, contentHash, 'feeds')).status).toBe(401);
    });
  });

  describe(`whose views with ${name}`, () => {
    test('a developer sees a version of another as one that is not there: the same 404, also when naming the other as uploader', async () => {
      const { contentHash } = await version(ada());
      const nothing = await list(bob(), '0'.repeat(64));
      expect(nothing.status).toBe(404);
      for (const answer of [
        await list(bob(), contentHash),
        await list(bob(), contentHash, { uploader: ada().name }),
        await read(bob(), contentHash, 'feeds'),
        await read(bob(), contentHash, 'feeds', { uploader: ada().name }),
      ]) {
        expect([answer.status, answer.body.error]).toEqual([404, 'definition_not_found']);
        expect(answer.body).toEqual(nothing.body);
      }
    });

    test('an admin who can see two uploaders of the same content is told to say whose (409 ambiguous_version); with `uploader` each is read on its own', async () => {
      const bytes = uniqueJar(setup.jars().views, fresh('shared'));
      const { contentHash } = await version(ada(), bytes);
      await version(bob(), bytes);
      const ambiguous = await list(root(), contentHash);
      expect([ambiguous.status, ambiguous.body.error]).toEqual([409, 'ambiguous_version']);
      expect([...ambiguous.body.uploaders].sort()).toEqual([ada().name, bob().name].sort());
      expect((await read(root(), contentHash, 'feeds')).body.error).toBe('ambiguous_version');

      await bind(root(), contentHash, await define(), bob().name);
      const theirs = await list(root(), contentHash, { uploader: ada().name });
      expect([theirs.status, theirs.body.uploader, theirs.body.views[0].status]).toEqual([200, ada().name, 'unbound']);
      const bobs = await list(root(), contentHash, { uploader: bob().name });
      expect([bobs.body.uploader, bobs.body.views[0].status]).toEqual([bob().name, 'ready']);
      expect((await list(ada(), contentHash)).body.views[0].status).toBe('unbound');
    });
  });
}
