// What the API of the resource roles and bindings promises (08-api.md, ADR-025, WI-1012), as tests
// that run on any server that claims to be the Engine: the Fake of test-support (always, in
// `npm test`) and a real packaged Engine (`npm run test:contract`). The pipeline page of the
// Console is made from these answers: the roles with their binding and state, the resources that
// may be chosen (`candidates`), whose bindings an admin sees (`uploaders`), and what a run is
// refused for. Every resource made is deleted afterwards and every version is of bytes no one has
// uploaded, so that the real Engine is left as it was and the tests can run again on it.

import { afterEach, describe, expect, test, vi } from 'vitest';
import { uniqueJar } from '../test-support/zip';
import type { PipelinesContractSetup } from './pipelines-contract';
import type { ContractCaller } from './system-contract';

interface Answer {
  status: number;
  body: any;
}

const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/;
const TERMINAL = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'TIMED_OUT'];

export function describeBindingsContract(name: string, setup: PipelinesContractSetup) {
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
      payload = typeof body === 'string' ? body : JSON.stringify(body);
    }
    const response = await fetch(`${setup.baseUrl()}${path}`, { method, headers, body: payload });
    const text = await response.text();
    let parsed: unknown = null;
    try {
      parsed = text === '' ? null : JSON.parse(text);
    } catch {
      parsed = text;
    }
    return { status: response.status, body: parsed };
  }

  let counter = 0;
  const fresh = (prefix: string) => `${prefix}-${Date.now().toString(36)}${(counter++).toString(36)}`;

  const ROLES = ['report-store', 'ticket-counter', 'scratch'];
  /**
   * A version of `demo-roles` made of bytes no one has uploaded, by [who]; [bytes] to upload the
   * same again. A binding belongs to the uploader and the pipeline's name, not to a version, so the
   * ones an earlier test left are taken away first (unless [keepBindings]).
   */
  const version = async (
    who: ContractCaller,
    bytes: Uint8Array = uniqueJar(setup.jars().roles, fresh('roles')),
    keepBindings = false,
  ) => {
    const answer = await call(who, 'POST', '/api/v1/artifacts', bytes);
    expect([200, 201]).toContain(answer.status);
    const contentHash = answer.body.contentHash as string;
    if (!keepBindings) {
      for (const role of ROLES) {
        expect((await call(who, 'DELETE', rolePath(contentHash, role))).status).toBe(200);
      }
    }
    return { contentHash, bytes };
  };

  const rolesPath = (contentHash: string, uploader?: string) =>
    `/api/v1/definitions/${contentHash}/demo-roles/resource-bindings${uploader === undefined ? '' : `?uploader=${uploader}`}`;
  const rolePath = (contentHash: string, role: string, uploader?: string) =>
    `/api/v1/definitions/${contentHash}/demo-roles/resource-bindings/${role}${uploader === undefined ? '' : `?uploader=${uploader}`}`;
  const read = (who: ContractCaller | null, contentHash: string, uploader?: string) =>
    call(who, 'GET', rolesPath(contentHash, uploader));
  const bind = (who: ContractCaller | null, contentHash: string, role: string, resource: string, uploader?: string) =>
    call(who, 'PUT', rolePath(contentHash, role, uploader), { resource });
  const unbind = (who: ContractCaller | null, contentHash: string, role: string, uploader?: string) =>
    call(who, 'DELETE', rolePath(contentHash, role, uploader));
  const roleOf = (answer: Answer, role: string) => answer.body.roles.find((r: any) => r.role === role);

  const made: string[] = [];
  /** A resource of [type], defined by the admin; deleted when the test is over. */
  async function define(type: 'counter' | 'file', options: { enabled?: boolean; resourceName?: string } = {}) {
    const resourceName = options.resourceName ?? fresh('bound');
    const body =
      type === 'file'
        ? { name: resourceName, capacity: 1, type, settings: { path: `${fresh('dir')}/out.txt` } }
        : { name: resourceName, capacity: 1, type };
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

  describe(`resource roles and their bindings with ${name}`, () => {
    test('a version says each of its roles: type, purpose, and, with nothing bound and nothing of the same name, that it is unbound', async () => {
      const { contentHash } = await version(ada());
      const answer = await read(ada(), contentHash);
      expect(answer.status).toBe(200);
      expect(answer.body).toMatchObject({ contentHash, uploader: ada().name, pipeline: 'demo-roles', uploaders: [ada().name], staleBindings: [] });
      expect(answer.body.roles.map((r: any) => r.role).sort()).toEqual(['report-store', 'scratch', 'ticket-counter']);
      expect(roleOf(answer, 'report-store')).toMatchObject({
        type: 'file',
        purpose: 'Where the weekly report is written',
        binding: null,
        resource: 'report-store',
        isDefault: true,
        exists: false,
        enabled: null,
        resourceType: null,
        typeMatches: null,
        problem: 'unbound',
      });
      expect(roleOf(answer, 'ticket-counter')).toMatchObject({ type: 'counter', purpose: 'Limits how many tickets are handled at once' });
      expect(roleOf(answer, 'scratch')).toMatchObject({ type: null, purpose: null });
    });

    test('a role with no binding takes the resource of its own name, and says so', async () => {
      const { contentHash } = await version(ada());
      await define('counter', { resourceName: 'scratch' });
      const role = roleOf(await read(ada(), contentHash), 'scratch');
      expect(role).toMatchObject({
        binding: null,
        resource: 'scratch',
        isDefault: true,
        exists: true,
        enabled: true,
        resourceType: 'counter',
        typeMatches: true,
        problem: null,
      });
    });

    test('the candidates of a role are the resources of its type, by name, disabled ones among them and said so; a role of no type is offered all', async () => {
      const { contentHash } = await version(ada());
      const counter = await define('counter');
      const off = await define('counter', { enabled: false });
      const file = await define('file');
      const answer = await read(ada(), contentHash);
      const counters = roleOf(answer, 'ticket-counter').candidates;
      expect(counters).toContainEqual({ name: counter, enabled: true });
      expect(counters).toContainEqual({ name: off, enabled: false });
      expect(counters.map((c: any) => c.name)).not.toContain(file);
      expect(counters.map((c: any) => c.name)).toEqual([...counters.map((c: any) => c.name)].sort());
      expect(roleOf(answer, 'report-store').candidates.map((c: any) => c.name)).toContain(file);
      expect(roleOf(answer, 'report-store').candidates.map((c: any) => c.name)).not.toContain(counter);
      const any = roleOf(answer, 'scratch').candidates.map((c: any) => c.name);
      expect(any).toEqual(expect.arrayContaining([counter, file]));
    });

    test('a developer, who cannot read the resources, is offered the candidates all the same', async () => {
      const { contentHash } = await version(ada());
      const counter = await define('counter');
      expect((await call(ada(), 'GET', '/api/v1/resources')).status).toBe(403);
      expect(roleOf(await read(ada(), contentHash), 'ticket-counter').candidates).toContainEqual({ name: counter, enabled: true });
    });

    test('binding a role says who and when, and the resource it now is; the answer is the state after the change', async () => {
      const { contentHash } = await version(ada());
      const counter = await define('counter');
      const answer = await bind(ada(), contentHash, 'ticket-counter', counter);
      expect(answer.status).toBe(200);
      const role = roleOf(answer, 'ticket-counter');
      expect(role).toMatchObject({
        binding: { resource: counter, setBy: ada().name },
        resource: counter,
        isDefault: false,
        exists: true,
        enabled: true,
        resourceType: 'counter',
        typeMatches: true,
        problem: null,
      });
      expect(role.binding.setAt).toMatch(ISO);
      expect(roleOf(await read(ada(), contentHash), 'ticket-counter').binding.resource).toBe(counter);
    });

    test('binding again replaces the resource', async () => {
      const { contentHash } = await version(ada());
      const first = await define('counter');
      const second = await define('counter');
      await bind(ada(), contentHash, 'ticket-counter', first);
      const answer = await bind(ada(), contentHash, 'ticket-counter', second);
      expect([answer.status, roleOf(answer, 'ticket-counter').binding.resource]).toEqual([200, second]);
    });

    test('a resource of another type is a 422 invalid_binding type_mismatch and nothing changes', async () => {
      const { contentHash } = await version(ada());
      const file = await define('file');
      const answer = await bind(ada(), contentHash, 'ticket-counter', file);
      expect([answer.status, answer.body.error, answer.body.problem]).toEqual([422, 'invalid_binding', 'type_mismatch']);
      expect(roleOf(await read(ada(), contentHash), 'ticket-counter').binding).toBeNull();
    });

    test('a role the version does not declare is a 422 invalid_binding unknown_role', async () => {
      const { contentHash } = await version(ada());
      const counter = await define('counter');
      const answer = await bind(ada(), contentHash, 'nope', counter);
      expect([answer.status, answer.body.error, answer.body.problem]).toEqual([422, 'invalid_binding', 'unknown_role']);
    });

    test('a resource that is not there is a 404 resource_not_found; a version or pipeline that is not there is a 404 definition_not_found', async () => {
      const { contentHash } = await version(ada());
      const none = await bind(ada(), contentHash, 'ticket-counter', fresh('missing'));
      expect([none.status, none.body.error]).toEqual([404, 'resource_not_found']);
      const nothing = await read(ada(), '0'.repeat(64));
      expect([nothing.status, nothing.body.error]).toEqual([404, 'definition_not_found']);
      const pipeline = await call(ada(), 'GET', `/api/v1/definitions/${contentHash}/nope/resource-bindings`);
      expect([pipeline.status, pipeline.body.error]).toEqual([404, 'definition_not_found']);
    });

    test('a disabled resource can be bound: the role says it is disabled, and the resource is offered as disabled', async () => {
      const { contentHash } = await version(ada());
      const off = await define('counter', { enabled: false });
      const answer = await bind(ada(), contentHash, 'ticket-counter', off);
      expect(answer.status).toBe(200);
      expect(roleOf(answer, 'ticket-counter')).toMatchObject({ resource: off, enabled: false, problem: 'disabled' });
    });

    test('unbinding takes the role back to the resource of its own name; unbinding what is not bound is a 200 that changes nothing', async () => {
      const { contentHash } = await version(ada());
      const counter = await define('counter');
      await bind(ada(), contentHash, 'ticket-counter', counter);
      const answer = await unbind(ada(), contentHash, 'ticket-counter');
      expect(answer.status).toBe(200);
      expect(roleOf(answer, 'ticket-counter')).toMatchObject({ binding: null, resource: 'ticket-counter', isDefault: true });
      expect((await unbind(ada(), contentHash, 'ticket-counter')).status).toBe(200);
    });

    test('a role that is neither declared nor bound is a 404 role_not_found', async () => {
      const { contentHash } = await version(ada());
      const answer = await unbind(ada(), contentHash, 'nope');
      expect([answer.status, answer.body.error]).toEqual([404, 'role_not_found']);
    });

    test('the bindings of an uploader are kept for their next version of the pipeline', async () => {
      const first = await version(ada());
      const counter = await define('counter');
      await bind(ada(), first.contentHash, 'ticket-counter', counter);
      const next = await version(ada(), undefined, true);
      expect(next.contentHash).not.toBe(first.contentHash);
      expect(roleOf(await read(ada(), next.contentHash), 'ticket-counter').binding?.resource).toBe(counter);
    });

    test('a resource names the pipelines and roles that are bound to it, and no longer the ones that are not', async () => {
      const { contentHash } = await version(ada());
      const counter = await define('counter');
      await bind(ada(), contentHash, 'ticket-counter', counter);
      const resource = (await call(root(), 'GET', `/api/v1/resources/${counter}`)).body;
      expect(resource.declaredBy.definitions).toContainEqual(
        expect.objectContaining({ contentHash, uploader: ada().name, pipeline: 'demo-roles', role: 'ticket-counter', declaredType: 'counter' }),
      );
      await unbind(ada(), contentHash, 'ticket-counter');
      const after = (await call(root(), 'GET', `/api/v1/resources/${counter}`)).body;
      expect(after.declaredBy.definitions.filter((d: any) => d.contentHash === contentHash)).toEqual([]);
    });

    test('nobody without a token reads or changes a binding: 401', async () => {
      const { contentHash } = await version(ada());
      expect((await read(null, contentHash)).status).toBe(401);
      expect((await call(null, 'PUT', rolePath(contentHash, 'ticket-counter'), { resource: 'x' })).status).toBe(401);
      expect((await call(null, 'DELETE', rolePath(contentHash, 'ticket-counter'))).status).toBe(401);
    });
  });

  describe(`whose bindings with ${name}`, () => {
    test('a developer sees a version of another as one that is not there: the same 404, also when naming the other as uploader', async () => {
      const { contentHash } = await version(ada());
      const own = await read(bob(), contentHash);
      const named = await read(bob(), contentHash, ada().name);
      const nothing = await read(bob(), '0'.repeat(64));
      expect([own.status, named.status, nothing.status]).toEqual([404, 404, 404]);
      expect(own.body).toEqual(nothing.body);
      expect(named.body).toEqual(nothing.body);
      const counter = await define('counter');
      const put = await bind(bob(), contentHash, 'ticket-counter', counter, ada().name);
      expect([put.status, put.body]).toEqual([404, nothing.body]);
    });

    test('an admin who can see two uploaders of the same content is told to say whose (409 ambiguous_version with the uploaders); with `uploader` each one has bindings of their own', async () => {
      const bytes = uniqueJar(setup.jars().roles, fresh('shared'));
      const { contentHash } = await version(ada(), bytes);
      await version(bob(), bytes);
      const counter = await define('counter');

      const ambiguous = await read(root(), contentHash);
      expect([ambiguous.status, ambiguous.body.error]).toEqual([409, 'ambiguous_version']);
      expect([...ambiguous.body.uploaders].sort()).toEqual([ada().name, bob().name].sort());

      const bound = await bind(root(), contentHash, 'ticket-counter', counter, bob().name);
      expect(bound.status).toBe(200);
      expect(bound.body.uploader).toBe(bob().name);
      expect([...bound.body.uploaders].sort()).toEqual([ada().name, bob().name].sort());
      expect(roleOf(bound, 'ticket-counter').binding).toMatchObject({ resource: counter, setBy: root().name });

      const theirs = await read(root(), contentHash, ada().name);
      expect(theirs.body.uploader).toBe(ada().name);
      expect(roleOf(theirs, 'ticket-counter').binding).toBeNull();
      expect(roleOf(await read(bob(), contentHash), 'ticket-counter').binding?.resource).toBe(counter);
      expect(roleOf(await read(ada(), contentHash), 'ticket-counter').binding).toBeNull();
      expect((await read(ada(), contentHash)).body.uploaders).toEqual([ada().name]);
    });
  });

  describe(`a run and the bindings of its roles with ${name}`, () => {
    test('a run is refused as unavailable for each role that has no resource, by role, with the resource it came to and what is wrong with it', async () => {
      const { contentHash } = await version(ada());
      const off = await define('counter', { enabled: false });
      await bind(ada(), contentHash, 'ticket-counter', off);
      const answer = await call(ada(), 'POST', '/api/v1/runs', { contentHash, pipeline: 'demo-roles' });
      expect([answer.status, answer.body.error]).toEqual([409, 'resources_unavailable']);
      expect(answer.body.problems).toContainEqual({ role: 'ticket-counter', resource: off, problem: 'disabled' });
      expect(answer.body.problems).toContainEqual({ role: 'report-store', resource: 'report-store', problem: 'unbound' });
    });

    test('a run is made once each role is bound to a resource that can be used', async () => {
      const { contentHash } = await version(ada());
      await bind(ada(), contentHash, 'ticket-counter', await define('counter'));
      await bind(ada(), contentHash, 'report-store', await define('file'));
      await bind(ada(), contentHash, 'scratch', await define('counter'));
      const answer = await call(ada(), 'POST', '/api/v1/runs', { contentHash, pipeline: 'demo-roles' });
      expect(answer.status).toBe(201);
      await vi.waitFor(
        async () => {
          const run = await call(ada(), 'GET', `/api/v1/runs/${answer.body.runId}`);
          expect(TERMINAL).toContain(run.body.state);
        },
        { timeout: 30_000, interval: 100 },
      );
    });
  });
}
