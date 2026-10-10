import { afterEach, describe, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { ApiFailure } from './failure';

// The roles of a pipeline and their bindings (ADR-025) as the Console reads and sends them, held
// against the Fake Engine (which the contract tests hold to the real one).

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };
const ada = { name: 'ada', role: 'developer' as const };
const HASH = 'cd'.repeat(32);
const pipeline = {
  name: 'report',
  className: 'x.Report',
  resources: ['store', 'counter'],
  resourceTypes: { store: 'file' },
  resourcePurposes: { store: 'Where the report is written' },
};
const start = async (identity: { name: string; role: 'admin' | 'developer' }, uploaders = ['ada']) => {
  app = await createTestApp({ identity });
  for (const who of uploaders) app.engine.backend.seedArtifact(who, [pipeline], { contentHash: HASH });
  app.engine.backend.defineResource('files', true);
  return app.context.api;
};

describe('the roles of a pipeline', () => {
  test('are read with their type, purpose, state and the resources to choose from', async () => {
    const api = await start(ada);
    app.engine.backend.resources.define('disk', { type: 'file' });
    app.engine.backend.resources.define('old-disk', { type: 'file', enabled: false });

    const read = await api.resourceBindings(HASH, 'report');

    expect(read.uploader).toBe('ada');
    expect(read.uploaders).toEqual(['ada']);
    expect(read.roles.map((r) => r.role)).toEqual(['store', 'counter']);
    expect(read.roles[0]).toMatchObject({
      role: 'store',
      type: 'file',
      purpose: 'Where the report is written',
      binding: null,
      resource: 'store',
      isDefault: true,
      exists: false,
      problem: 'unbound',
      candidates: [
        { name: 'disk', enabled: true },
        { name: 'old-disk', enabled: false },
      ],
    });
    expect(read.roles[1]).toMatchObject({ type: null, purpose: null });
    expect(read.staleBindings).toEqual([]);
  });

  test('are bound to a resource, replaced and unbound; each answer is the state afterwards', async () => {
    const api = await start(ada);
    app.engine.backend.resources.define('disk', { type: 'file' });
    app.engine.backend.resources.define('disk-2', { type: 'file' });

    const bound = await api.bindResource(HASH, 'report', 'store', 'disk');
    expect(bound.roles[0]).toMatchObject({
      binding: { resource: 'disk', setBy: 'ada' },
      resource: 'disk',
      isDefault: false,
      problem: null,
    });

    const replaced = await api.bindResource(HASH, 'report', 'store', 'disk-2');
    expect(replaced.roles[0].binding?.resource).toBe('disk-2');

    const unbound = await api.unbindResource(HASH, 'report', 'store');
    expect(unbound.roles[0]).toMatchObject({ binding: null, isDefault: true, resource: 'store' });
  });

  test('a refusal is a failure with its code: invalid_binding with the problem, resource_not_found, role_not_found', async () => {
    const api = await start(ada);
    app.engine.backend.resources.define('count', { type: 'counter' });

    expect(await api.bindResource(HASH, 'report', 'store', 'count').catch((e) => e)).toMatchObject({
      status: 422,
      body: { error: 'invalid_binding', problem: 'type_mismatch' },
    });
    expect(await api.bindResource(HASH, 'report', 'nope', 'count').catch((e) => e)).toMatchObject({
      status: 422,
      body: { error: 'invalid_binding', problem: 'unknown_role' },
    });
    expect(await api.bindResource(HASH, 'report', 'store', 'gone').catch((e) => e)).toMatchObject({
      status: 404,
      body: { error: 'resource_not_found' },
    });
    expect(await api.unbindResource(HASH, 'report', 'nope').catch((e) => e)).toMatchObject({
      status: 404,
      body: { error: 'role_not_found' },
    });
    expect(await api.resourceBindings(HASH, 'nope').catch((e) => e)).toBeInstanceOf(ApiFailure);
  });

  test('of an uploader are asked for by name: an admin who can see several is told ambiguous_version without it', async () => {
    const api = await start(root, ['ada', 'bob']);
    app.engine.backend.resources.define('disk', { type: 'file' });

    expect(await api.resourceBindings(HASH, 'report').catch((e) => e)).toMatchObject({
      status: 409,
      body: { error: 'ambiguous_version', uploaders: ['ada', 'bob'] },
    });
    const bound = await api.bindResource(HASH, 'report', 'store', 'disk', 'bob');
    expect(bound.uploader).toBe('bob');
    expect(bound.uploaders).toEqual(['ada', 'bob']);
    expect((await api.resourceBindings(HASH, 'report', 'ada')).roles[0].binding).toBeNull();
    expect((await api.unbindResource(HASH, 'report', 'store', 'bob')).roles[0].binding).toBeNull();
  });

  test('a name goes into the address as one piece, whatever it holds', async () => {
    const api = await start(root, ['a&b=c']);
    app.engine.backend.resources.define('x/y', { type: 'file' });
    app.engine.backend.seedArtifact('a&b=c', [{ ...pipeline, name: 'p.q', resources: ['r/s'] }], {
      contentHash: HASH,
    });

    const read = await api.resourceBindings(HASH, 'p.q', 'a&b=c');
    expect(read.uploader).toBe('a&b=c');
    expect(read.roles[0].role).toBe('r/s');
    await api.unbindResource(HASH, 'p.q', 'r/s', 'a&b=c');
    expect(app.engine.log.at(-1)!.path).toBe(
      `/api/v1/definitions/${HASH}/p.q/resource-bindings/r%2Fs?uploader=a%26b%3Dc`,
    );
  });

  test('an answer that is not as 08-api.md says is a failure, not a broken screen', async () => {
    const api = await start(ada);
    app.engine.faults.push({
      match: /GET \/api\/v1\/definitions\/.*\/resource-bindings/,
      status: 200,
      body: { contentHash: HASH, roles: 'none' },
      times: 1,
    });

    expect(await api.resourceBindings(HASH, 'report').catch((e) => e)).toMatchObject({ status: 0 });
  });
});
