// What the API of the admin pages promises (08-api.md: triggers and their firings, the webhook
// entrance, the allow-list, shared resources, the setting of unsafe execution and deleting a
// version), as tests that run on any server that claims to be the Engine: the Fake of test-support
// (always, in `npm test`) and a real packaged Engine (`npm run test:contract`). Only what both must
// do is asserted. The real Engine keeps what a test makes (a trigger, an allow-list entry, a shared
// resource that cannot be deleted): every name is made new for the run, and a test that changes the
// allow-list or a setting puts it back, so that the tests can run again on the same Engine.

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
const ISO = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$/;

export function describeAdminContract(name: string, setup: PipelinesContractSetup) {
  const developers = () => setup.callers().filter((c) => c.role === 'developer');
  const ada = () => developers()[0];
  const root = () => setup.callers().find((c) => c.role === 'admin')!;

  async function call(
    who: ContractCaller | null,
    method: string,
    path: string,
    body?: unknown,
    headers: Record<string, string> = {},
  ): Promise<Answer> {
    const sent: Record<string, string> = { ...headers };
    if (who) sent.Authorization = `Bearer ${who.token}`;
    let payload: BodyInit | undefined;
    if (ArrayBuffer.isView(body)) {
      sent['Content-Type'] = 'application/octet-stream';
      payload = body as unknown as BodyInit;
    } else if (body !== undefined) {
      sent['Content-Type'] = 'application/json';
      payload = typeof body === 'string' ? body : JSON.stringify(body);
    }
    const response = await fetch(`${setup.baseUrl()}${path}`, { method, headers: sent, body: payload });
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
  const fresh = (prefix: string) =>
    `${prefix}-${Date.now().toString(36)}${(counter++).toString(36)}`;

  async function uploaded(bytes: Uint8Array) {
    const answer = await call(ada(), 'POST', '/api/v1/artifacts', bytes);
    expect([200, 201]).toContain(answer.status);
    return answer.body as { contentHash: string; pipelines: Array<Record<string, any>> };
  }

  const runOf = (who: ContractCaller, runId: string) => call(who, 'GET', `/api/v1/runs/${runId}`);
  const untilTerminal = (runId: string) =>
    vi.waitFor(
      async () => {
        const { body } = await runOf(ada(), runId);
        expect(TERMINAL).toContain(body.state);
        return body;
      },
      { timeout: 40_000, interval: 100 },
    );

  const webhook = (triggerName: string, secret: string | null, delivery: string | null) => {
    const headers: Record<string, string> = {};
    if (secret !== null) headers['X-Runline-Webhook-Secret'] = secret;
    if (delivery !== null) headers['X-Runline-Delivery-Id'] = delivery;
    return call(null, 'POST', `/api/v1/webhooks/${triggerName}`, '{}', headers);
  };

  const firingsOf = async (triggerName: string) =>
    (await call(root(), 'GET', `/api/v1/triggers/${triggerName}/firings`)).body.firings as any[];

  describe(`triggers with ${name}`, () => {
    async function slow() {
      return (await uploaded(setup.jars().slow)).contentHash;
    }
    const cron = (contentHash: string, extra: object = {}) => ({
      name: fresh('cron'),
      kind: 'cron',
      contentHash,
      pipeline: 'demo-slow',
      cron: '*/5 * * * *',
      timeZone: 'Asia/Taipei',
      parameters: { steps: '1' },
      ...extra,
    });
    const hook = (contentHash: string, extra: object = {}) => ({
      name: fresh('hook'),
      kind: 'webhook',
      contentHash,
      pipeline: 'demo-slow',
      // A delivery makes a run: a short one, so that the Engine's runs are not all taken.
      parameters: { steps: '1', delayMillis: '10' },
      ...extra,
    });

    test('a cron trigger is made with its schedule, its parameters and the defaults they are completed with, and has no secret', async () => {
      const contentHash = await slow();
      const body = cron(contentHash);
      const answer = await call(root(), 'POST', '/api/v1/triggers', body);
      expect(answer.status).toBe(201);
      expect(answer.headers.get('location')).toBe(`/api/v1/triggers/${body.name}`);
      expect(answer.body.secret ?? null).toBeNull();
      expect(answer.body.trigger).toMatchObject({
        name: body.name,
        kind: 'cron',
        contentHash,
        pipeline: 'demo-slow',
        parameters: { steps: '1' },
        effectiveParameters: { label: 'demo', steps: '1', delayMillis: '1000' },
        enabled: true,
        cron: '*/5 * * * *',
        timeZone: 'Asia/Taipei',
        webhookPath: null,
        secretConfigured: false,
        secretRotatedAt: null,
        createdBy: root().name,
        updatedBy: root().name,
      });
      expect(answer.body.trigger.createdAt).toMatch(ISO);
      expect(answer.body.trigger.updatedAt).toMatch(ISO);
    });

    test('a cron trigger without a time zone has none given back as UTC, and can be made disabled', async () => {
      const contentHash = await slow();
      const body = cron(contentHash, { timeZone: undefined, enabled: false });
      const answer = await call(root(), 'POST', '/api/v1/triggers', body);
      expect(answer.status).toBe(201);
      expect(answer.body.trigger.enabled).toBe(false);
      expect(answer.body.trigger.timeZone).toBe('UTC');
    });

    test('a webhook trigger shows its secret once: in the answer that makes it, and nowhere after', async () => {
      const contentHash = await slow();
      const body = hook(contentHash);
      const answer = await call(root(), 'POST', '/api/v1/triggers', body);
      expect(answer.status).toBe(201);
      expect(typeof answer.body.secret).toBe('string');
      expect(answer.body.secret.length).toBeGreaterThanOrEqual(16);
      expect(answer.body.trigger).toMatchObject({
        kind: 'webhook',
        cron: null,
        timeZone: null,
        webhookPath: `/api/v1/webhooks/${body.name}`,
        secretConfigured: true,
      });
      expect(answer.body.trigger.secretRotatedAt).toMatch(ISO);

      const read = await call(root(), 'GET', `/api/v1/triggers/${body.name}`);
      const listed = await call(root(), 'GET', '/api/v1/triggers');
      expect(JSON.stringify(read.body)).not.toContain(answer.body.secret);
      expect(JSON.stringify(listed.body)).not.toContain(answer.body.secret);
      expect(read.body.secretConfigured).toBe(true);
      expect(listed.body.triggers.map((t: any) => t.name)).toContain(body.name);
    });

    test('a trigger that is not there is a 404 trigger_not_found, to read, change, delete, rotate and list the firings of', async () => {
      const missing = fresh('missing');
      for (const [method, path, body] of [
        ['GET', `/api/v1/triggers/${missing}`, undefined],
        ['PATCH', `/api/v1/triggers/${missing}`, { enabled: true }],
        ['DELETE', `/api/v1/triggers/${missing}`, undefined],
        ['POST', `/api/v1/triggers/${missing}/rotate-secret`, undefined],
        ['GET', `/api/v1/triggers/${missing}/firings`, undefined],
      ] as const) {
        const answer = await call(root(), method, path, body);
        expect([path, answer.status, answer.body.error]).toEqual([path, 404, 'trigger_not_found']);
      }
    });

    test('what is wrong with a trigger is said in `problem`: the name, the cron expression, the time zone, a schedule it must not have', async () => {
      const contentHash = await slow();
      const refused = async (body: object) => {
        const answer = await call(root(), 'POST', '/api/v1/triggers', body);
        expect(answer.status).toBe(422);
        expect(answer.body.error).toBe('invalid_trigger');
        return answer.body.problem;
      };
      expect(await refused(cron(contentHash, { name: '-bad' }))).toBe('name');
      expect(await refused(cron(contentHash, { cron: undefined }))).toBe('cron_required');
      expect(await refused(cron(contentHash, { cron: 'not a cron' }))).toBe('cron_expression');
      expect(await refused(cron(contentHash, { timeZone: 'Mars/Olympus' }))).toBe('time_zone');
      expect(await refused(hook(contentHash, { cron: '* * * * *' }))).toBe('schedule_not_allowed');
      expect(await refused(hook(contentHash, { timeZone: 'UTC' }))).toBe('schedule_not_allowed');
    });

    test('a name in use is a 409 trigger_exists; a version or pipeline that is not there is a 404 definition_not_found; parameters the pipeline does not declare are a 422 invalid_parameters, each named; a kind that is none is a 400', async () => {
      const contentHash = await slow();
      const body = cron(contentHash);
      expect((await call(root(), 'POST', '/api/v1/triggers', body)).status).toBe(201);
      const again = await call(root(), 'POST', '/api/v1/triggers', body);
      expect([again.status, again.body.error]).toEqual([409, 'trigger_exists']);

      const nothing = await call(root(), 'POST', '/api/v1/triggers', cron('0'.repeat(64)));
      expect([nothing.status, nothing.body.error]).toEqual([404, 'definition_not_found']);
      const wrong = await call(root(), 'POST', '/api/v1/triggers', cron(contentHash, { pipeline: 'nope' }));
      expect([wrong.status, wrong.body.error]).toEqual([404, 'definition_not_found']);

      const undeclared = await call(
        root(),
        'POST',
        '/api/v1/triggers',
        cron(contentHash, { parameters: { nope: '1' } }),
      );
      expect([undeclared.status, undeclared.body.error]).toEqual([422, 'invalid_parameters']);
      expect(undeclared.body.problems).toEqual([{ name: 'nope', problem: 'undeclared' }]);

      const kind = await call(root(), 'POST', '/api/v1/triggers', cron(contentHash, { kind: 'banana' }));
      expect([kind.status, kind.body.error]).toEqual([400, 'bad_request']);
      const garbage = await call(root(), 'POST', '/api/v1/triggers', 'garbage');
      expect([garbage.status, garbage.body.error]).toEqual([400, 'bad_request']);
    });

    test('a trigger is changed in the parts that are given: enabled, the schedule, the parameters; who changed it is the one who did', async () => {
      const contentHash = await slow();
      const made = cron(contentHash);
      await call(root(), 'POST', '/api/v1/triggers', made);

      const off = await call(root(), 'PATCH', `/api/v1/triggers/${made.name}`, { enabled: false });
      expect(off.status).toBe(200);
      expect(off.body).toMatchObject({ name: made.name, enabled: false, cron: '*/5 * * * *' });

      const moved = await call(root(), 'PATCH', `/api/v1/triggers/${made.name}`, {
        cron: '0 9 * * 1-5',
        timeZone: 'Europe/Berlin',
        parameters: { steps: '2', label: 'x' },
        enabled: true,
      });
      expect(moved.status).toBe(200);
      expect(moved.body).toMatchObject({
        enabled: true,
        cron: '0 9 * * 1-5',
        timeZone: 'Europe/Berlin',
        parameters: { steps: '2', label: 'x' },
        effectiveParameters: { label: 'x', steps: '2', delayMillis: '1000' },
        updatedBy: root().name,
        createdBy: root().name,
      });
      const read = await call(root(), 'GET', `/api/v1/triggers/${made.name}`);
      expect(read.body.cron).toBe('0 9 * * 1-5');
    });

    test('a change that is not valid changes nothing, and says what is wrong', async () => {
      const contentHash = await slow();
      const made = cron(contentHash);
      await call(root(), 'POST', '/api/v1/triggers', made);
      const path = `/api/v1/triggers/${made.name}`;

      for (const [body, problem] of [
        [{}, 'nothing_to_change'],
        [{ cron: 'x' }, 'cron_expression'],
        [{ timeZone: 'Mars/X' }, 'time_zone'],
      ] as const) {
        const answer = await call(root(), 'PATCH', path, body);
        expect([answer.status, answer.body.error, answer.body.problem]).toEqual([
          422,
          'invalid_trigger',
          problem,
        ]);
      }
      const undeclared = await call(root(), 'PATCH', path, { parameters: { nope: '1' } });
      expect([undeclared.status, undeclared.body.error]).toEqual([422, 'invalid_parameters']);
      const unchanged = (await call(root(), 'GET', path)).body;
      expect(unchanged).toMatchObject({ cron: '*/5 * * * *', timeZone: 'Asia/Taipei', parameters: { steps: '1' } });
    });

    test('a webhook has no schedule to change: a cron on it is a 422 schedule_not_allowed', async () => {
      const contentHash = await slow();
      const made = hook(contentHash);
      await call(root(), 'POST', '/api/v1/triggers', made);
      const answer = await call(root(), 'PATCH', `/api/v1/triggers/${made.name}`, { cron: '* * * * *' });
      expect([answer.status, answer.body.problem]).toEqual([422, 'schedule_not_allowed']);
    });

    test('rotating the secret of a webhook gives a new one that works, and the old one stops working at once; a cron trigger has none to rotate: 409 not_a_webhook', async () => {
      const contentHash = await slow();
      const made = hook(contentHash);
      const first = (await call(root(), 'POST', '/api/v1/triggers', made)).body.secret as string;
      expect((await webhook(made.name, first, fresh('d'))).status).toBe(202);

      const rotated = await call(root(), 'POST', `/api/v1/triggers/${made.name}/rotate-secret`);
      expect(rotated.status).toBe(200);
      const second = rotated.body.secret as string;
      expect(second).not.toBe(first);
      expect(rotated.body.trigger).toMatchObject({ name: made.name, secretConfigured: true });
      expect(JSON.stringify(rotated.body.trigger)).not.toContain(second);

      expect((await webhook(made.name, first, fresh('d'))).status).toBe(401);
      expect((await webhook(made.name, second, fresh('d'))).status).toBe(202);

      const cronMade = cron(contentHash);
      await call(root(), 'POST', '/api/v1/triggers', cronMade);
      const refused = await call(root(), 'POST', `/api/v1/triggers/${cronMade.name}/rotate-secret`);
      expect([refused.status, refused.body.error]).toEqual([409, 'not_a_webhook']);
    });

    test('the webhook entrance answers 202 to the right secret and a delivery id, and each delivery makes one run that the firing names; the same delivery again makes none', async () => {
      const contentHash = await slow();
      const made = hook(contentHash);
      const { secret } = (await call(root(), 'POST', '/api/v1/triggers', made)).body;
      const delivery = fresh('delivery');

      const accepted = await webhook(made.name, secret, delivery);
      expect(accepted.status).toBe(202);
      expect(accepted.body).toEqual({ status: 'accepted' });
      const again = await webhook(made.name, secret, delivery);
      expect(again.status).toBe(202);

      const [firing, ...others] = await vi.waitFor(async () => {
        const list = await firingsOf(made.name);
        expect(list.length).toBeGreaterThan(0);
        expect(list[0].outcome).not.toBe('pending');
        return list;
      });
      expect(others).toEqual([]);
      expect(firing).toMatchObject({ deliveryId: delivery, outcome: 'run_created', scheduledFor: null });
      expect(firing.reason ?? null).toBeNull();
      expect(firing.firedAt).toMatch(ISO);
      expect(firing.runId).toMatch(/^[0-9a-f-]{36}$/);

      const run = await untilTerminal(firing.runId);
      expect(run).toMatchObject({
        source: { kind: 'TRIGGER', name: made.name },
        pipeline: 'demo-slow',
        state: 'SUCCEEDED',
      });
    });

    test('the webhook entrance refuses what is not right: a wrong or missing secret is a 401, a delivery id that is missing is a 400 invalid_delivery_id, a disabled trigger is a 401', async () => {
      const contentHash = await slow();
      const made = hook(contentHash);
      const { secret } = (await call(root(), 'POST', '/api/v1/triggers', made)).body;

      expect((await webhook(made.name, 'wrong', fresh('d'))).status).toBe(401);
      expect((await webhook(made.name, null, fresh('d'))).status).toBe(401);
      const noDelivery = await webhook(made.name, secret, null);
      expect([noDelivery.status, noDelivery.body.error]).toEqual([400, 'invalid_delivery_id']);

      await call(root(), 'PATCH', `/api/v1/triggers/${made.name}`, { enabled: false });
      expect((await webhook(made.name, secret, fresh('d'))).status).toBe(401);
      expect((await webhook(fresh('nobody'), secret, fresh('d'))).status).toBe(401);
    });

    test('a delivery that makes no run is a firing that was refused, with its reason as a code and its detail', async () => {
      const unsafe = await uploaded(setup.jars().unsafe);
      const made = hook(unsafe.contentHash, { pipeline: 'demo-unsafe', parameters: {} });
      const { secret } = (await call(root(), 'POST', '/api/v1/triggers', made)).body;
      expect((await webhook(made.name, secret, fresh('d'))).status).toBe(202);

      const [firing] = await vi.waitFor(async () => {
        const list = await firingsOf(made.name);
        expect(list.length).toBeGreaterThan(0);
        expect(list[0].outcome).not.toBe('pending');
        return list;
      });
      expect(firing).toMatchObject({ outcome: 'refused', reason: 'unsafe_not_allowed', runId: null });
      expect(typeof firing.detail).toBe('string');
    });

    test('the firings are listed newest first and can be limited', async () => {
      const contentHash = await slow();
      const made = hook(contentHash);
      const { secret } = (await call(root(), 'POST', '/api/v1/triggers', made)).body;
      const ids = [fresh('a'), fresh('b'), fresh('c')];
      for (const id of ids) expect((await webhook(made.name, secret, id)).status).toBe(202);

      const list = await vi.waitFor(async () => {
        const read = await firingsOf(made.name);
        expect(read).toHaveLength(3);
        expect(read.every((f) => f.outcome !== 'pending')).toBe(true);
        return read;
      });
      expect(list.map((f) => f.deliveryId)).toEqual([...ids].reverse());
      const one = await call(root(), 'GET', `/api/v1/triggers/${made.name}/firings?limit=1`);
      expect(one.body.firings.map((f: any) => f.deliveryId)).toEqual([ids[2]]);
    });

    test('a trigger is deleted with its firings: 204, then 404', async () => {
      const contentHash = await slow();
      const made = hook(contentHash);
      await call(root(), 'POST', '/api/v1/triggers', made);
      const gone = await call(root(), 'DELETE', `/api/v1/triggers/${made.name}`);
      expect(gone.status).toBe(204);
      expect((await call(root(), 'GET', `/api/v1/triggers/${made.name}`)).status).toBe(404);
      expect((await call(root(), 'GET', `/api/v1/triggers/${made.name}/firings`)).status).toBe(404);
      const listed = (await call(root(), 'GET', '/api/v1/triggers')).body.triggers;
      expect(listed.map((t: any) => t.name)).not.toContain(made.name);
    });

    test('only an admin: a developer is a 403 forbidden and nobody is a 401, for every call', async () => {
      const contentHash = await slow();
      const made = cron(contentHash);
      await call(root(), 'POST', '/api/v1/triggers', made);
      for (const [method, path, body] of [
        ['POST', '/api/v1/triggers', cron(contentHash)],
        ['GET', '/api/v1/triggers', undefined],
        ['GET', `/api/v1/triggers/${made.name}`, undefined],
        ['PATCH', `/api/v1/triggers/${made.name}`, { enabled: false }],
        ['DELETE', `/api/v1/triggers/${made.name}`, undefined],
        ['POST', `/api/v1/triggers/${made.name}/rotate-secret`, undefined],
        ['GET', `/api/v1/triggers/${made.name}/firings`, undefined],
      ] as const) {
        const asDeveloper = await call(ada(), method, path, body);
        expect([path, asDeveloper.status, asDeveloper.body?.error]).toEqual([path, 403, 'forbidden']);
        expect([path, (await call(null, method, path, body)).status]).toEqual([path, 401]);
      }
      expect((await call(root(), 'GET', `/api/v1/triggers/${made.name}`)).status).toBe(200);
    });
  });

  const entryPath = (kind: string, entryName: string) =>
    `/api/v1/allowlist/entries/${kind}/${encodeURIComponent(entryName)}`;
  const freshClass = () => `contract.t${Date.now().toString(36)}${(counter++).toString(36)}.Thing`;
  const freshPackage = () => `contract.p${Date.now().toString(36)}${(counter++).toString(36)}`;
  const allowList = async () => (await call(root(), 'GET', '/api/v1/allowlist')).body;
  const versionsOf = async () => (await call(root(), 'GET', '/api/v1/allowlist/versions')).body.versions as any[];
  const definitionsOf = async (contentHash: string) =>
    ((await call(root(), 'GET', '/api/v1/definitions')).body.definitions as any[]).filter(
      (d) => d.contentHash === contentHash,
    );

  describe(`the allow-list with ${name}`, () => {
    test('the allow-list in force: its version, who changed it and when, every entry with its kind, name and exactOnly, and what the verdicts do not cover', async () => {
      const list = await allowList();
      expect(typeof list.version).toBe('string');
      expect(Number(list.version)).toBeGreaterThanOrEqual(1);
      expect(typeof list.changedBy).toBe('string');
      expect(list.changedAt).toMatch(ISO);
      expect(typeof list.limitations).toBe('string');
      expect(list.entries.length).toBeGreaterThan(0);
      for (const entry of list.entries) {
        expect(['package', 'class']).toContain(entry.kind);
        expect(typeof entry.name).toBe('string');
        expect(entry.kind === 'class' ? entry.exactOnly : typeof entry.exactOnly).toBe(
          entry.kind === 'class' ? null : 'boolean',
        );
        for (const key of ['createdBy', 'createdAt', 'updatedBy', 'updatedAt']) {
          expect(typeof entry[key]).toBe('string');
        }
      }
    });

    test('the history of versions is newest first, each with who, when, what was done and how many definitions were judged again', async () => {
      const versions = await versionsOf();
      expect(versions.length).toBeGreaterThan(0);
      const numbers = versions.map((v) => Number(v.version));
      expect(numbers).toEqual([...numbers].sort((a, b) => b - a));
      expect(versions[0].version).toBe((await allowList()).version);
      for (const version of versions) {
        expect(['INITIAL', 'ENTRY_ADDED', 'ENTRY_CHANGED', 'ENTRY_REMOVED']).toContain(version.action);
        expect(typeof version.changedBy).toBe('string');
        expect(version.changedAt).toMatch(ISO);
        expect(typeof version.detail).toBe('string');
        for (const count of ['rejudgedDefinitions', 'becameUnsafe', 'becameSafe']) {
          expect(Number.isInteger(version[count])).toBe(true);
        }
      }
      const one = await call(root(), 'GET', '/api/v1/allowlist/versions?limit=1');
      expect(one.body.versions).toHaveLength(1);
      expect(one.body.versions[0].version).toBe(versions[0].version);
    });

    test('an entry is added after a preview that changes nothing: 200 with preview true, then 201 with the new version, a Location and the entry as the admin made it', async () => {
      const className = freshClass();
      const before = await allowList();

      const preview = await call(root(), 'POST', '/api/v1/allowlist/entries?preview=true', {
        kind: 'class',
        name: className,
      });
      expect(preview.status).toBe(200);
      expect(preview.body).toMatchObject({ preview: true, version: before.version, entry: null });
      expect(preview.body.redundantEntries).toEqual([]);
      expect(typeof preview.body.limitations).toBe('string');
      for (const count of ['examinedArtifacts', 'examinedDefinitions', 'becameUnsafe', 'becameSafe', 'unreadable']) {
        expect(Number.isInteger(preview.body.impact[count])).toBe(true);
      }
      expect(Array.isArray(preview.body.impact.changes)).toBe(true);
      const unchanged = await allowList();
      expect(unchanged.version).toBe(before.version);
      expect(unchanged.entries.map((e: any) => e.name)).not.toContain(className);

      const added = await call(root(), 'POST', '/api/v1/allowlist/entries', { kind: 'class', name: className });
      try {
        expect(added.status).toBe(201);
        expect(added.headers.get('location')).toBe(entryPath('class', className));
        expect(added.body.preview).toBe(false);
        expect(Number(added.body.version)).toBe(Number(before.version) + 1);
        expect(added.body.entry).toMatchObject({
          kind: 'class',
          name: className,
          exactOnly: null,
          createdBy: root().name,
          updatedBy: root().name,
        });
        const after = await allowList();
        expect(after.version).toBe(added.body.version);
        expect(after.changedBy).toBe(root().name);
        expect(after.entries.map((e: any) => e.name)).toContain(className);
        const read = await call(root(), 'GET', entryPath('class', className));
        expect(read.status).toBe(200);
        expect(read.body.name).toBe(className);
        const [latest] = await versionsOf();
        expect(latest).toMatchObject({
          version: added.body.version,
          action: 'ENTRY_ADDED',
          changedBy: root().name,
        });
      } finally {
        await call(root(), 'DELETE', entryPath('class', className));
      }
      const [removed] = await versionsOf();
      expect(removed.action).toBe('ENTRY_REMOVED');
    });

    test('an entry that exists is a 409 entry_exists with the one that is there; one that another covers is a 409 entry_covered with the one that covers it', async () => {
      const pkg = freshPackage();
      expect((await call(root(), 'POST', '/api/v1/allowlist/entries', { kind: 'package', name: pkg })).status).toBe(201);
      try {
        const exists = await call(root(), 'POST', '/api/v1/allowlist/entries', { kind: 'package', name: pkg });
        expect([exists.status, exists.body.error]).toEqual([409, 'entry_exists']);
        expect(exists.body.existing).toMatchObject({ kind: 'package', name: pkg });

        const covered = await call(root(), 'POST', '/api/v1/allowlist/entries', {
          kind: 'class',
          name: `${pkg}.sub.Thing`,
        });
        expect([covered.status, covered.body.error]).toEqual([409, 'entry_covered']);
        expect(covered.body.coveredBy).toMatchObject({ kind: 'package', name: pkg });
        const preview = await call(root(), 'POST', '/api/v1/allowlist/entries?preview=true', {
          kind: 'class',
          name: `${pkg}.sub.Thing`,
        });
        expect([preview.status, preview.body.error]).toEqual([409, 'entry_covered']);
      } finally {
        await call(root(), 'DELETE', entryPath('package', pkg));
      }
    });

    test('an entry that is not valid is a 422 invalid_entry that says which part: the name, or exactOnly on a class; a kind that is none, or a preview that is neither true nor false, is a 400', async () => {
      const add = (body: object, query = '') => call(root(), 'POST', `/api/v1/allowlist/entries${query}`, body);
      const noPackage = await add({ kind: 'class', name: 'NoPackage' });
      expect([noPackage.status, noPackage.body.error, noPackage.body.problem]).toEqual([422, 'invalid_entry', 'name']);
      const badPackage = await add({ kind: 'package', name: '1bad..name' });
      expect([badPackage.status, badPackage.body.problem]).toEqual([422, 'name']);
      const exact = await add({ kind: 'class', name: `${freshPackage()}.Thing`, exactOnly: true });
      expect([exact.status, exact.body.problem]).toEqual([422, 'exact_only_on_class']);

      const kind = await add({ kind: 'nope', name: 'a.B' });
      expect([kind.status, kind.body.error]).toEqual([400, 'bad_request']);
      const preview = await add({ kind: 'class', name: `${freshPackage()}.Thing` }, '?preview=maybe');
      expect([preview.status, preview.body.error]).toEqual([400, 'bad_request']);
      const garbage = await call(root(), 'POST', '/api/v1/allowlist/entries', 'garbage');
      expect([garbage.status, garbage.body.error]).toEqual([400, 'bad_request']);
    });

    test('an entry that is not there is a 404 entry_not_found, to read, change and remove; a kind in the address that is none is a 400', async () => {
      const missing = freshClass();
      for (const [method, body] of [
        ['GET', undefined],
        ['PATCH', { exactOnly: true }],
        ['DELETE', undefined],
      ] as const) {
        const answer = await call(root(), method, entryPath('class', missing), body);
        expect([method, answer.status, answer.body.error]).toEqual([method, 404, 'entry_not_found']);
      }
      const kind = await call(root(), 'GET', entryPath('nope', missing));
      expect([kind.status, kind.body.error]).toEqual([400, 'bad_request']);
    });

    test('an entry is changed after a preview too: exactOnly of a package, its name; nothing to change is a 422', async () => {
      const pkg = freshPackage();
      const renamed = freshPackage();
      expect((await call(root(), 'POST', '/api/v1/allowlist/entries', { kind: 'package', name: pkg })).status).toBe(201);
      let current = pkg;
      try {
        const before = await allowList();
        const preview = await call(root(), 'PATCH', `${entryPath('package', pkg)}?preview=true`, { exactOnly: true });
        expect(preview.status).toBe(200);
        expect(preview.body).toMatchObject({ preview: true, version: before.version, entry: null });
        expect((await call(root(), 'GET', entryPath('package', pkg))).body.exactOnly).toBe(false);

        const exact = await call(root(), 'PATCH', entryPath('package', pkg), { exactOnly: true });
        expect(exact.status).toBe(200);
        expect(exact.body.preview).toBe(false);
        expect(exact.body.entry).toMatchObject({ name: pkg, exactOnly: true, updatedBy: root().name });
        expect(Number(exact.body.version)).toBe(Number(before.version) + 1);
        expect((await versionsOf())[0].action).toBe('ENTRY_CHANGED');

        const moved = await call(root(), 'PATCH', entryPath('package', pkg), { name: renamed });
        expect(moved.status).toBe(200);
        current = renamed;
        expect(moved.body.entry).toMatchObject({ name: renamed, exactOnly: true });
        expect((await call(root(), 'GET', entryPath('package', pkg))).status).toBe(404);

        const nothing = await call(root(), 'PATCH', entryPath('package', renamed), {});
        expect([nothing.status, nothing.body.error, nothing.body.problem]).toEqual([422, 'invalid_entry', 'nothing_to_change']);
      } finally {
        await call(root(), 'DELETE', entryPath('package', current));
      }
    });

    test('an entry that makes others unnecessary names them in redundantEntries and leaves them where they are', async () => {
      const pkg = freshPackage();
      const inner = `${pkg}.sub.Thing`;
      expect((await call(root(), 'POST', '/api/v1/allowlist/entries', { kind: 'class', name: inner })).status).toBe(201);
      try {
        const added = await call(root(), 'POST', '/api/v1/allowlist/entries', { kind: 'package', name: pkg });
        expect(added.status).toBe(201);
        expect(added.body.redundantEntries.map((e: any) => e.name)).toEqual([inner]);
        expect((await allowList()).entries.map((e: any) => e.name)).toContain(inner);
        await call(root(), 'DELETE', entryPath('package', pkg));
      } finally {
        await call(root(), 'DELETE', entryPath('class', inner));
      }
    });

    test('removing an entry that a pipeline needs: the preview says which definitions become UNSAFE and changes nothing; applying judges them again, takes back their unsafe execution, and putting the entry back makes them SAFE again', async () => {
      const slow = await uploaded(setup.jars().slow);
      const failing = await uploaded(setup.jars().failing);
      const path = entryPath('class', 'java.io.PrintStream');
      if ((await call(root(), 'GET', path)).status === 404) {
        expect((await call(root(), 'POST', '/api/v1/allowlist/entries', { kind: 'class', name: 'java.io.PrintStream' })).status).toBe(201);
      }
      const allow = (contentHash: string, pipeline: string, value: boolean) =>
        call(root(), 'PUT', `/api/v1/definitions/${contentHash}/${pipeline}/unsafe-execution`, { allow: value });
      expect((await allow(failing.contentHash, 'demo-failing', true)).status).toBe(200);
      const before = await allowList();
      let removed = false;
      try {
        const preview = await call(root(), 'DELETE', `${path}?preview=true`);
        expect(preview.status).toBe(200);
        expect(preview.body).toMatchObject({ preview: true, version: before.version, entry: null });
        const changes = preview.body.impact.changes as any[];
        const ofSlow = changes.find((c) => c.contentHash === slow.contentHash && c.pipeline === 'demo-slow');
        const ofFailing = changes.find((c) => c.contentHash === failing.contentHash && c.pipeline === 'demo-failing');
        expect(ofSlow).toMatchObject({
          className: 'samples.slow.SlowPipeline',
          from: 'SAFE',
          to: 'UNSAFE',
          allowUnsafeExecution: false,
          unsafeExecutionRevoked: false,
        });
        expect(ofFailing).toMatchObject({ from: 'SAFE', to: 'UNSAFE', allowUnsafeExecution: true, unsafeExecutionRevoked: true });
        expect(preview.body.impact.becameUnsafe).toBeGreaterThanOrEqual(2);
        expect(preview.body.impact.becameSafe).toBe(0);
        expect(preview.body.impact.examinedDefinitions).toBeGreaterThanOrEqual(2);
        expect((await definitionsOf(slow.contentHash))[0].verdict).toBe('SAFE');
        expect((await allowList()).version).toBe(before.version);

        const applied = await call(root(), 'DELETE', path);
        removed = true;
        expect(applied.status).toBe(200);
        expect(applied.body).toMatchObject({ preview: false, entry: null });
        expect(Number(applied.body.version)).toBe(Number(before.version) + 1);
        expect(applied.body.impact.becameUnsafe).toBe(preview.body.impact.becameUnsafe);

        const [now] = await definitionsOf(slow.contentHash);
        expect(now.verdict).toBe('UNSAFE');
        expect(now.allowListVersion).toBe(applied.body.version);
        expect(now.reasons).toContainEqual(
          expect.objectContaining({ kind: 'NOT_ALLOW_LISTED', className: 'java.io.PrintStream' }),
        );
        const [revoked] = await definitionsOf(failing.contentHash);
        expect(revoked.allowUnsafeExecution).toBe(false);

        const [latest] = await versionsOf();
        expect(latest).toMatchObject({ version: applied.body.version, action: 'ENTRY_REMOVED', changedBy: root().name });
        expect(latest.becameUnsafe).toBeGreaterThanOrEqual(2);
        expect(latest.rejudgedDefinitions).toBeGreaterThanOrEqual(latest.becameUnsafe);
      } finally {
        if (removed) {
          const back = await call(root(), 'POST', '/api/v1/allowlist/entries', { kind: 'class', name: 'java.io.PrintStream' });
          expect(back.status).toBe(201);
          expect(back.body.impact.becameSafe).toBeGreaterThanOrEqual(2);
          expect((await definitionsOf(slow.contentHash))[0].verdict).toBe('SAFE');
          expect((await versionsOf())[0]).toMatchObject({ action: 'ENTRY_ADDED' });
        }
      }
    });

    test('a manual recheck: the preview changes nothing, applying does not make a version', async () => {
      const before = await allowList();
      const preview = await call(root(), 'POST', '/api/v1/allowlist/recheck?preview=true');
      expect(preview.status).toBe(200);
      expect(preview.body).toMatchObject({ preview: true, version: before.version, entry: null });
      expect(Array.isArray(preview.body.impact.changes)).toBe(true);
      const applied = await call(root(), 'POST', '/api/v1/allowlist/recheck');
      expect(applied.status).toBe(200);
      expect(applied.body).toMatchObject({ preview: false, version: before.version, entry: null });
      expect((await allowList()).version).toBe(before.version);
      const bad = await call(root(), 'POST', '/api/v1/allowlist/recheck?preview=maybe');
      expect([bad.status, bad.body.error]).toEqual([400, 'bad_request']);
    });

    test('only an admin: a developer is a 403 forbidden and nobody is a 401, for every call', async () => {
      const className = freshClass();
      for (const [method, path, body] of [
        ['GET', '/api/v1/allowlist', undefined],
        ['GET', '/api/v1/allowlist/versions', undefined],
        ['POST', '/api/v1/allowlist/entries', { kind: 'class', name: className }],
        ['GET', entryPath('class', className), undefined],
        ['PATCH', entryPath('class', className), { name: className }],
        ['DELETE', entryPath('class', className), undefined],
        ['POST', '/api/v1/allowlist/recheck', undefined],
      ] as const) {
        const asDeveloper = await call(ada(), method, path, body);
        expect([method, path, asDeveloper.status, asDeveloper.body?.error]).toEqual([method, path, 403, 'forbidden']);
        expect([method, path, (await call(null, method, path, body)).status]).toEqual([method, path, 401]);
      }
    });
  });


  const allowUnsafe = (contentHash: string, pipeline: string, allow: unknown) =>
    call(root(), 'PUT', `/api/v1/definitions/${contentHash}/${pipeline}/unsafe-execution`, { allow });
  const variant = (bytes: Uint8Array) => uploaded(uniqueJar(bytes, fresh('v')));

  describe(`unsafe execution with ${name}`, () => {
    test('an admin allows an UNSAFE pipeline to run: the answer says who and when, the pipeline says it is allowed, a run can be made and says who allowed it; taking it back refuses runs again', async () => {
      const { contentHash } = await variant(setup.jars().unsafe);
      const create = () =>
        call(ada(), 'POST', '/api/v1/runs', { contentHash, pipeline: 'demo-unsafe' });
      expect((await create()).body.error).toBe('unsafe_not_allowed');
      try {
        const allowed = await allowUnsafe(contentHash, 'demo-unsafe', true);
        expect(allowed.status).toBe(200);
        expect(allowed.body).toMatchObject({ contentHash, pipeline: 'demo-unsafe', allow: true, setBy: root().name });
        expect(allowed.body.setAt).toMatch(ISO);
        const [definition] = await definitionsOf(contentHash);
        expect(definition).toMatchObject({ verdict: 'UNSAFE', allowUnsafeExecution: true });

        const made = await create();
        expect(made.status).toBe(201);
        expect(made.body.unsafeExecution).toMatchObject({ setBy: root().name });
        const run = await untilTerminal(made.body.runId);
        expect(run.unsafeExecution.setBy).toBe(root().name);
      } finally {
        const back = await allowUnsafe(contentHash, 'demo-unsafe', false);
        expect(back.body.allow).toBe(false);
      }
      expect((await create()).body.error).toBe('unsafe_not_allowed');
    });

    test('each version has its own setting: another version of the same pipeline is not allowed because this one is', async () => {
      const one = await variant(setup.jars().unsafe);
      const two = await variant(setup.jars().unsafe);
      try {
        await allowUnsafe(one.contentHash, 'demo-unsafe', true);
        expect((await definitionsOf(one.contentHash))[0].allowUnsafeExecution).toBe(true);
        expect((await definitionsOf(two.contentHash))[0].allowUnsafeExecution).toBe(false);
      } finally {
        await allowUnsafe(one.contentHash, 'demo-unsafe', false);
      }
    });

    test('a version or pipeline that is not there is a 404 definition_not_found; a body that is not {"allow": true or false} is a 400 bad_request', async () => {
      const { contentHash } = await uploaded(setup.jars().unsafe);
      for (const [hash, pipeline] of [
        ['0'.repeat(64), 'demo-unsafe'],
        [contentHash, 'nope'],
      ]) {
        const answer = await allowUnsafe(hash, pipeline, true);
        expect([answer.status, answer.body.error]).toEqual([404, 'definition_not_found']);
      }
      const notBoolean = await allowUnsafe(contentHash, 'demo-unsafe', 'yes');
      expect([notBoolean.status, notBoolean.body.error]).toEqual([400, 'bad_request']);
      const garbage = await call(root(), 'PUT', `/api/v1/definitions/${contentHash}/demo-unsafe/unsafe-execution`, 'garbage');
      expect([garbage.status, garbage.body.error]).toEqual([400, 'bad_request']);
    });

    test('only an admin: a developer is a 403 forbidden and nobody is a 401', async () => {
      const { contentHash } = await uploaded(setup.jars().unsafe);
      const path = `/api/v1/definitions/${contentHash}/demo-unsafe/unsafe-execution`;
      const asDeveloper = await call(ada(), 'PUT', path, { allow: true });
      expect([asDeveloper.status, asDeveloper.body.error]).toEqual([403, 'forbidden']);
      expect((await call(null, 'PUT', path, { allow: true })).status).toBe(401);
      expect((await definitionsOf(contentHash))[0].allowUnsafeExecution).toBe(false);
    });
  });

  describe(`deleting a version with ${name}`, () => {
    const versionPath = (contentHash: string) => `/api/v1/artifacts/${contentHash}`;

    test('a version that nothing refers to is deleted: 204, and it is gone as a version and as definitions', async () => {
      const { contentHash } = await variant(setup.jars().slow);
      expect((await definitionsOf(contentHash)).length).toBe(1);
      const gone = await call(root(), 'DELETE', versionPath(contentHash));
      expect(gone.status).toBe(204);
      const read = await call(ada(), 'GET', versionPath(contentHash));
      expect([read.status, read.body.error]).toEqual([404, 'not_found']);
      expect(await definitionsOf(contentHash)).toEqual([]);
      const again = await call(root(), 'DELETE', versionPath(contentHash));
      expect([again.status, again.body.error]).toEqual([404, 'not_found']);
    });

    test('a version that a run refers to is a 409 in_use and stays', async () => {
      const { contentHash } = await variant(setup.jars().slow);
      const made = await call(ada(), 'POST', '/api/v1/runs', {
        contentHash,
        pipeline: 'demo-slow',
        parameters: { steps: '1', delayMillis: '10' },
      });
      expect(made.status).toBe(201);
      await untilTerminal(made.body.runId);
      const refused = await call(root(), 'DELETE', versionPath(contentHash));
      expect([refused.status, refused.body.error]).toEqual([409, 'in_use']);
      expect((await call(ada(), 'GET', versionPath(contentHash))).status).toBe(200);
    });

    test('a version that a trigger refers to is a 409 in_use until the trigger is deleted', async () => {
      const { contentHash } = await variant(setup.jars().slow);
      const triggerName = fresh('bound');
      const made = await call(root(), 'POST', '/api/v1/triggers', {
        name: triggerName,
        kind: 'webhook',
        contentHash,
        pipeline: 'demo-slow',
      });
      expect(made.status).toBe(201);
      const refused = await call(root(), 'DELETE', versionPath(contentHash));
      expect([refused.status, refused.body.error]).toEqual([409, 'in_use']);
      expect((await call(root(), 'DELETE', `/api/v1/triggers/${triggerName}`)).status).toBe(204);
      expect((await call(root(), 'DELETE', versionPath(contentHash))).status).toBe(204);
    });

    test('only an admin: a developer is a 403 forbidden, even for his own version, and nobody is a 401', async () => {
      const { contentHash } = await variant(setup.jars().slow);
      const asDeveloper = await call(ada(), 'DELETE', versionPath(contentHash));
      expect([asDeveloper.status, asDeveloper.body.error]).toEqual([403, 'forbidden']);
      expect((await call(null, 'DELETE', versionPath(contentHash))).status).toBe(401);
      expect((await call(ada(), 'GET', versionPath(contentHash))).status).toBe(200);
      await call(root(), 'DELETE', versionPath(contentHash));
    });
  });

  describe(`shared resources with ${name}`, () => {
    const resourcePath = (resource: string) => `/api/v1/resources/${resource}`;
    const create = (body: unknown) => call(root(), 'POST', '/api/v1/resources', body);
    const resource = async (resourceName: string) => (await call(root(), 'GET', resourcePath(resourceName))).body;

    test('a resource is defined with its capacity: 201 with a Location, enabled, by the admin, nobody holding it or waiting for it', async () => {
      const resourceName = fresh('res');
      const answer = await create({ name: resourceName, capacity: 2 });
      expect(answer.status).toBe(201);
      expect(answer.headers.get('location')).toBe(resourcePath(resourceName));
      expect(answer.body).toMatchObject({
        name: resourceName,
        capacity: 2,
        enabled: true,
        createdBy: root().name,
        updatedBy: root().name,
        holders: [],
        waiters: [],
      });
      expect(answer.body.createdAt).toMatch(ISO);
      expect((await resource(resourceName)).capacity).toBe(2);
      const listed = (await call(root(), 'GET', '/api/v1/resources')).body.resources;
      expect(listed.find((r: any) => r.name === resourceName)).toMatchObject({ capacity: 2 });
    });

    test('a resource defined with a name and a capacity is a counter: no settings, no secret alias, no limit or use of its own, never checked, and declared by nobody', async () => {
      const resourceName = fresh('res');
      const answer = await create({ name: resourceName, capacity: 1 });
      const expected = {
        type: 'counter',
        settings: {},
        secretAlias: null,
        secretStatus: 'not_set',
        trustStatus: [],
        clientCertStatus: 'not_set',
        concurrencyLimit: null,
        usage: null,
        lastCheck: null,
        declaredBy: { count: 0, triggers: 0, definitions: [] },
      };
      expect(answer.body).toMatchObject(expected);
      expect(await resource(resourceName)).toMatchObject(expected);
      const typed = await create({ name: fresh('res'), capacity: 1, type: 'counter' });
      expect([typed.status, typed.body.type]).toEqual([201, 'counter']);
    });

    test('a resource names the definitions that declare it, by version and pipeline, with the type each expects and the triggers bound to it', async () => {
      const { contentHash } = await uploaded(setup.jars().resource);
      await printer(1);
      const declaring = (body: any) =>
        body.declaredBy.definitions.find(
          (d: any) => d.contentHash === contentHash && d.uploader === ada().name && d.pipeline === 'demo-resource',
        );
      const before = await resource('demo-printer');
      expect(declaring(before)).toMatchObject({ declaredType: null });
      expect(before.declaredBy.count).toBe(before.declaredBy.definitions.length);

      const trigger = fresh('cron');
      const made = await call(root(), 'POST', '/api/v1/triggers', {
        name: trigger,
        kind: 'cron',
        contentHash,
        uploader: ada().name,
        pipeline: 'demo-resource',
        cron: '0 3 1 1 *',
        enabled: false,
      });
      expect(made.status).toBe(201);
      try {
        const after = await resource('demo-printer');
        expect(declaring(after).triggers).toBe(declaring(before).triggers + 1);
        expect(after.declaredBy.triggers).toBe(before.declaredBy.triggers + 1);
      } finally {
        await call(root(), 'DELETE', `/api/v1/triggers/${trigger}`);
      }
    });

    test('a definition that expects a type is named with that type, and its pipeline says when the type is not the resource\'s', async () => {
      const typedVersion = await uploaded(setup.jars().typed);
      const plainVersion = await uploaded(setup.jars().resource);
      await printer(1);
      const typed = (await resource('demo-printer')).declaredBy.definitions.find(
        (d: any) => d.contentHash === typedVersion.contentHash && d.uploader === ada().name,
      );
      expect(typed).toMatchObject({ pipeline: 'demo-typed', declaredType: 'file' });

      const pipelineOf = async (contentHash: string, pipeline: string) =>
        (await call(ada(), 'GET', `/api/v1/artifacts/${contentHash}`)).body.pipelines.find(
          (p: any) => p.name === pipeline,
        );
      const typedPipeline = await pipelineOf(typedVersion.contentHash, 'demo-typed');
      expect(typedPipeline.metadata.resources).toEqual(['demo-printer']);
      expect(typedPipeline.metadata.resourceTypes).toEqual({ 'demo-printer': 'file' });
      expect(typedPipeline.warnings).toContainEqual(
        expect.objectContaining({ kind: 'resource_type_mismatch', resource: 'demo-printer' }),
      );
      const plainPipeline = await pipelineOf(plainVersion.contentHash, 'demo-resource');
      expect(plainPipeline.metadata.resourceTypes).toEqual({});
      expect(plainPipeline.warnings).toEqual([]);
    });

    test('a check of a counter passes, and is kept as the last check, which a change of capacity or of being enabled keeps; a resource that is not there is a 404', async () => {
      const resourceName = fresh('res');
      await create({ name: resourceName, capacity: 1 });
      const checked = await call(root(), 'POST', `${resourcePath(resourceName)}/check`);
      expect(checked.status).toBe(200);
      // A check also says what it found of the certificates the resource uses (WI-52): none here.
      expect(checked.body).toMatchObject({ ok: true, failure: null, certificates: [], warnings: [] });
      expect(checked.body.checkedAt).toMatch(ISO);
      // The time is answered as it is kept (WI-56): the last check read back says the same text.
      const sameCheck = (kept: any) => {
        expect(kept).toEqual({ ok: true, failure: null, checkedAt: checked.body.checkedAt });
      };
      sameCheck((await resource(resourceName)).lastCheck);
      const listed = (await call(root(), 'GET', '/api/v1/resources')).body.resources;
      sameCheck(listed.find((r: any) => r.name === resourceName).lastCheck);

      await call(root(), 'PATCH', resourcePath(resourceName), { capacity: 2, enabled: false });
      sameCheck((await resource(resourceName)).lastCheck);

      const missing = await call(root(), 'POST', `${resourcePath(fresh('missing'))}/check`);
      expect([missing.status, missing.body.error]).toEqual([404, 'resource_not_found']);
    });

    test('a name in use is a 409 resource_exists; a name or capacity that is not valid is a 422 invalid_resource; a body that is not JSON is a 400', async () => {
      const resourceName = fresh('res');
      await create({ name: resourceName, capacity: 1 });
      const again = await create({ name: resourceName, capacity: 1 });
      expect([again.status, again.body.error]).toEqual([409, 'resource_exists']);
      for (const body of [
        { name: '-bad', capacity: 1 },
        { name: fresh('res'), capacity: 0 },
      ]) {
        const answer = await create(body);
        expect([answer.status, answer.body.error]).toEqual([422, 'invalid_resource']);
      }
      const garbage = await call(root(), 'POST', '/api/v1/resources', 'garbage');
      expect([garbage.status, garbage.body.error]).toEqual([400, 'bad_request']);
    });

    test('a resource that is not there is a 404 resource_not_found, to read, change and release a holder of', async () => {
      const missing = fresh('missing');
      for (const [method, path, body] of [
        ['GET', resourcePath(missing), undefined],
        ['PATCH', resourcePath(missing), { capacity: 2 }],
        ['POST', `${resourcePath(missing)}/holders/00000000-0000-4000-8000-000000000000/release`, undefined],
      ] as const) {
        const answer = await call(root(), method, path, body);
        expect([path, answer.status, answer.body.error]).toEqual([path, 404, 'resource_not_found']);
      }
    });

    test('the capacity and whether it is enabled are changed, each alone or both; who changed it is the one who did; nothing to change, or a capacity under one, is a 422', async () => {
      const resourceName = fresh('res');
      await create({ name: resourceName, capacity: 1 });
      const off = await call(root(), 'PATCH', resourcePath(resourceName), { enabled: false });
      expect(off.status).toBe(200);
      expect(off.body).toMatchObject({ capacity: 1, enabled: false, updatedBy: root().name });
      const both = await call(root(), 'PATCH', resourcePath(resourceName), { capacity: 3, enabled: true });
      expect(both.body).toMatchObject({ capacity: 3, enabled: true });
      for (const body of [{}, { capacity: 0 }]) {
        const answer = await call(root(), 'PATCH', resourcePath(resourceName), body);
        expect([JSON.stringify(body), answer.status, answer.body.error]).toEqual([JSON.stringify(body), 422, 'invalid_resource']);
      }
      expect(await resource(resourceName)).toMatchObject({ capacity: 3, enabled: true });
    });

    /** `demo-printer`, which the sample pipeline declares, with this capacity, enabled. */
    async function printer(capacity: number) {
      const there = await call(root(), 'GET', resourcePath('demo-printer'));
      const answer =
        there.status === 404
          ? await create({ name: 'demo-printer', capacity })
          : await call(root(), 'PATCH', resourcePath('demo-printer'), { capacity, enabled: true });
      expect([200, 201]).toContain(answer.status);
    }
    const startPrinterRun = async (contentHash: string) => {
      const made = await call(ada(), 'POST', '/api/v1/runs', { contentHash, pipeline: 'demo-resource' });
      expect(made.status).toBe(201);
      return made.body.runId as string;
    };
    const cancelAll = async (runIds: string[]) => {
      for (const runId of runIds) await call(ada(), 'POST', `/api/v1/runs/${runId}/cancel`);
      for (const runId of runIds) await untilTerminal(runId);
    };
    const untilPrinter = (holders: number, waiters: number) =>
      vi.waitFor(
        async () => {
          const now = await resource('demo-printer');
          expect([now.holders.length, now.waiters.length]).toEqual([holders, waiters]);
          return now;
        },
        { timeout: 20_000, interval: 100 },
      );

    test('who holds a resource and who waits for it, in the order they are served: with the time, and the run says it is waiting; a holder that is forced to let go is no holder and the first waiter holds it, while its run goes on', async () => {
      const { contentHash } = await uploaded(setup.jars().resource);
      await printer(1);
      await untilPrinter(0, 0);
      const first = await startPrinterRun(contentHash);
      await untilPrinter(1, 0);
      const second = await startPrinterRun(contentHash);
      const held = await untilPrinter(1, 1);
      try {
        expect(held.holders[0]).toMatchObject({ runId: first, pipeline: 'demo-resource' });
        expect(held.holders[0].heldSince).toMatch(ISO);
        expect(typeof held.holders[0].heldSeconds).toBe('number');
        expect(held.waiters[0]).toMatchObject({ runId: second, pipeline: 'demo-resource', waitingFor: ['demo-printer'] });
        expect(held.waiters[0].waitingSince).toMatch(ISO);
        expect(typeof held.waiters[0].waitedSeconds).toBe('number');
        expect((await runOf(ada(), second)).body.state).toBe('WAITING_FOR_RESOURCES');

        const notHolder = await call(root(), 'POST', `${resourcePath('demo-printer')}/holders/${second}/release`);
        expect([notHolder.status, notHolder.body.error]).toEqual([404, 'not_a_holder']);
        const notRun = await call(root(), 'POST', `${resourcePath('demo-printer')}/holders/not-a-run/release`);
        expect([notRun.status, notRun.body.error]).toEqual([404, 'not_a_holder']);

        const released = await call(root(), 'POST', `${resourcePath('demo-printer')}/holders/${first}/release`);
        expect(released.status).toBe(200);
        expect(released.body).toMatchObject({ resource: 'demo-printer', runId: first, pipeline: 'demo-resource' });
        expect(released.body.heldSince).toBe(held.holders[0].heldSince);

        const after = await untilPrinter(1, 0);
        expect(after.holders[0].runId).toBe(second);
        const stillRunning = (await runOf(ada(), first)).body.state;
        expect(['RUNNING', 'SUCCEEDED']).toContain(stillRunning);
        const again = await call(root(), 'POST', `${resourcePath('demo-printer')}/holders/${first}/release`);
        expect([again.status, again.body.error]).toEqual([404, 'not_a_holder']);
      } finally {
        await cancelAll([first, second]);
      }
    });

    test('a lower capacity takes nothing back from the holders; disabling the resource fails the runs that wait for it and no more', async () => {
      const { contentHash } = await uploaded(setup.jars().resource);
      await printer(2);
      await untilPrinter(0, 0);
      const first = await startPrinterRun(contentHash);
      const second = await startPrinterRun(contentHash);
      const third = await startPrinterRun(contentHash);
      await untilPrinter(2, 1);
      try {
        const lowered = await call(root(), 'PATCH', resourcePath('demo-printer'), { capacity: 1 });
        expect(lowered.status).toBe(200);
        expect(lowered.body.holders).toHaveLength(2);
        expect(lowered.body.waiters).toHaveLength(1);

        const off = await call(root(), 'PATCH', resourcePath('demo-printer'), { enabled: false });
        expect(off.body.enabled).toBe(false);
        const failed = await vi.waitFor(
          async () => {
            const { body } = await runOf(ada(), third);
            expect(body.state).toBe('FAILED');
            return body;
          },
          { timeout: 20_000, interval: 100 },
        );
        expect(typeof failed.failure.type).toBe('string');
        expect(['RUNNING', 'SUCCEEDED']).toContain((await runOf(ada(), first)).body.state);
      } finally {
        await call(root(), 'PATCH', resourcePath('demo-printer'), { capacity: 1, enabled: true });
        await cancelAll([first, second]);
      }
    });

    test('a preview of deleting a resource says what declares it and whether it is in use, and changes nothing; deleting it then leaves it nowhere', async () => {
      const resourceName = fresh('res');
      await create({ name: resourceName, capacity: 1 });
      const preview = await call(root(), 'DELETE', `${resourcePath(resourceName)}?preview=true`);
      expect(preview.status).toBe(200);
      expect(preview.body).toEqual({
        resource: resourceName,
        definitions: 0,
        triggers: 0,
        holders: 0,
        waiters: 0,
        inUse: false,
      });
      expect((await call(root(), 'GET', resourcePath(resourceName))).status).toBe(200);

      const deleted = await call(root(), 'DELETE', resourcePath(resourceName));
      expect(deleted.status).toBe(204);
      const gone = await call(root(), 'GET', resourcePath(resourceName));
      expect([gone.status, gone.body.error]).toEqual([404, 'resource_not_found']);
      const listed = (await call(root(), 'GET', '/api/v1/resources')).body.resources;
      expect(listed.some((r: any) => r.name === resourceName)).toBe(false);
      const again = await create({ name: resourceName, capacity: 2 });
      expect(again.status).toBe(201);
    });

    test('deleting a resource that is not there is a 404, previewed or not; a preview that is not true or false is a 400', async () => {
      const missing = fresh('missing');
      for (const path of [resourcePath(missing), `${resourcePath(missing)}?preview=true`]) {
        const answer = await call(root(), 'DELETE', path);
        expect([path, answer.status, answer.body.error]).toEqual([path, 404, 'resource_not_found']);
      }
      const resourceName = fresh('res');
      await create({ name: resourceName, capacity: 1 });
      const bad = await call(root(), 'DELETE', `${resourcePath(resourceName)}?preview=maybe`);
      expect([bad.status, bad.body.error]).toEqual([400, 'bad_request']);
      expect((await call(root(), 'GET', resourcePath(resourceName))).status).toBe(200);
    });

    test('a resource that a run holds is not deleted: the preview says it is in use and counts what declares it, and the delete is a 409 resource_in_use with the holders and waiters', async () => {
      const { contentHash } = await uploaded(setup.jars().resource);
      await printer(1);
      await untilPrinter(0, 0);
      const run = await startPrinterRun(contentHash);
      const held = await untilPrinter(1, 0);
      try {
        const preview = await call(root(), 'DELETE', `${resourcePath('demo-printer')}?preview=true`);
        expect(preview.body).toEqual({
          resource: 'demo-printer',
          definitions: held.declaredBy.count,
          triggers: held.declaredBy.triggers,
          holders: 1,
          waiters: 0,
          inUse: true,
        });
        const refused = await call(root(), 'DELETE', resourcePath('demo-printer'));
        expect([refused.status, refused.body.error, refused.body.holders, refused.body.waiters]).toEqual([
          409,
          'resource_in_use',
          1,
          0,
        ]);
        expect((await resource('demo-printer')).holders).toHaveLength(1);
      } finally {
        await cancelAll([run]);
      }
    });

    test('only an admin: a developer is a 403 forbidden and nobody is a 401, for every call', async () => {
      const resourceName = fresh('res');
      await create({ name: resourceName, capacity: 1 });
      for (const [method, path, body] of [
        ['POST', '/api/v1/resources', { name: fresh('res'), capacity: 1 }],
        ['GET', '/api/v1/resources', undefined],
        ['GET', resourcePath(resourceName), undefined],
        ['PATCH', resourcePath(resourceName), { capacity: 2 }],
        ['POST', `${resourcePath(resourceName)}/holders/00000000-0000-4000-8000-000000000000/release`, undefined],
        ['POST', `${resourcePath(resourceName)}/check`, undefined],
        ['DELETE', `${resourcePath(resourceName)}?preview=true`, undefined],
        ['DELETE', resourcePath(resourceName), undefined],
      ] as const) {
        const asDeveloper = await call(ada(), method, path, body);
        expect([method, path, asDeveloper.status, asDeveloper.body?.error]).toEqual([method, path, 403, 'forbidden']);
        expect([method, path, (await call(null, method, path, body)).status]).toEqual([method, path, 401]);
      }
    });
  });

  describe(`the settings of each type of resource with ${name}`, () => {
    // What the forms of the Console send (WI-50) and what they read back: the settings of each type
    // as the Engine writes them, and the `problem` of what it refuses. Each resource is deleted at
    // the end, so that the real Engine is left as it was.
    const resourcePath = (resource: string) => `/api/v1/resources/${resource}`;
    const create = (body: Record<string, unknown>) => call(root(), 'POST', '/api/v1/resources', body);
    const change = (resourceName: string, body: unknown) => call(root(), 'PATCH', resourcePath(resourceName), body);
    const made: string[] = [];
    const defined = async (body: Record<string, unknown>) => {
      const answer = await create(body);
      if (answer.status === 201) made.push(body.name as string);
      return answer;
    };
    afterEach(async () => {
      for (const resourceName of made.splice(0)) await call(root(), 'DELETE', resourcePath(resourceName));
    });
    const refused = async (answer: Promise<Answer>, problem: string) => {
      const { status, body } = await answer;
      expect([status, body?.error, body?.problem]).toEqual([422, 'invalid_resource', problem]);
    };

    test('a file is defined with its path under the resource root, as it was given; it has no secret, no limit and no use of its own', async () => {
      const resourceName = fresh('file');
      const path = `${fresh('dir')}/out.txt`;
      const answer = await defined({ name: resourceName, type: 'file', capacity: 1, settings: { path } });
      expect(answer.status).toBe(201);
      expect(answer.body).toMatchObject({
        type: 'file',
        settings: { path },
        secretAlias: null,
        secretStatus: 'not_set',
        concurrencyLimit: null,
        usage: null,
      });
    });

    test('a file refuses a path out of the resource root as path_outside_root, no path as invalid_settings, and any secret alias', async () => {
      const file = (settings: unknown, extra: Record<string, unknown> = {}) =>
        defined({ name: fresh('file'), type: 'file', capacity: 1, settings, ...extra });
      await refused(file({ path: '/etc/passwd' }), 'path_outside_root');
      await refused(file({ path: '../outside.txt' }), 'path_outside_root');
      await refused(file({ path: 'a/../../outside.txt' }), 'path_outside_root');
      await refused(file({}), 'invalid_settings');
      await refused(file({ path: '' }), 'invalid_settings');
      await refused(file({ path: 'a.txt', mode: 'rw' }), 'invalid_settings');
      await refused(file({ path: 'a.txt' }, { secretAlias: 'some-key' }), 'invalid_secret_alias');
    });

    const database = { kind: 'postgresql', host: '127.0.0.1', database: 'orders', username: 'reader' };

    test('a jdbc-pool is written with every effective value; its pool is the capacity times the connections per run, none of which is in use', async () => {
      const plain = await defined({ name: fresh('db'), type: 'jdbc-pool', capacity: 3, settings: database });
      expect(plain.status).toBe(201);
      expect(plain.body).toMatchObject({
        type: 'jdbc-pool',
        settings: {
          ...database,
          port: 5432,
          connectionsPerRun: 1,
          timeouts: { connectMs: 10000, statementMs: 300000, quotaWaitMs: 60000 },
          maxRows: 10000,
          maxResponseBytes: 8388608,
        },
        secretAlias: null,
        secretStatus: 'not_set',
        concurrencyLimit: 3,
        usage: { activeConnections: 0 },
      });
      expect(plain.body.settings.properties).toBeUndefined();

      const full = {
        ...database,
        port: 6543,
        connectionsPerRun: 2,
        timeouts: { connectMs: 2000, statementMs: 30000, quotaWaitMs: 5000 },
        properties: { ApplicationName: 'reports', currentSchema: 'sales,public', tcpKeepAlive: 'true' },
      };
      const given = await defined({
        name: fresh('db'),
        type: 'jdbc-pool',
        capacity: 2,
        settings: full,
        secretAlias: 'Orders-Pass',
      });
      expect(given.status).toBe(201);
      expect(given.body).toMatchObject({
        settings: { ...full, maxRows: 10000, maxResponseBytes: 8388608 },
        secretAlias: 'orders-pass',
        concurrencyLimit: 4,
      });
    });

    test('a jdbc-pool says what it refuses: the kind of database, a property that is not allowed, a limit, a timeout, an address, a missing field, any other field', async () => {
      const pool = (settings: Record<string, unknown>, extra: Record<string, unknown> = {}) =>
        defined({ name: fresh('db'), type: 'jdbc-pool', capacity: 1, settings, ...extra });
      await refused(pool({ ...database, kind: 'mysql' }), 'unsupported_database');
      await refused(pool({ ...database, kind: 'PostgreSQL' }), 'unsupported_database');
      await refused(pool({ ...database, properties: { password: 'x' } }), 'property_not_allowed');
      await refused(pool({ ...database, properties: { sslmode: 'disable' } }), 'property_not_allowed');
      await refused(pool({ ...database, properties: { tcpKeepAlive: 'maybe' } }), 'invalid_settings');
      await refused(pool({ ...database, connectionsPerRun: 65 }), 'invalid_limit');
      await refused(pool({ ...database, connectionsPerRun: 0 }), 'invalid_limit');
      await refused(pool({ ...database, timeouts: { statementMs: 0 } }), 'invalid_timeout');
      await refused(pool({ ...database, timeouts: { idleMs: 10 } }), 'invalid_timeout');
      await refused(pool({ ...database, host: 'db/other' }), 'invalid_settings');
      await refused(pool({ ...database, host: 'db:5432' }), 'invalid_settings');
      await refused(pool({ ...database, port: 70000 }), 'invalid_settings');
      await refused(pool({ kind: 'postgresql', host: 'db', database: 'orders' }), 'invalid_settings');
      await refused(pool({ ...database, url: 'jdbc:postgresql://db/orders' }), 'invalid_settings');
      await refused(pool({ ...database }, { secretAlias: '-bad' }), 'invalid_secret_alias');
    });

    const DEFAULT_ENDPOINTS = ['chat.completions', 'completions', 'embeddings', 'models.list', 'models.retrieve'];

    test('an openai-compatible service is written with every effective value, its endpoints in the order of the catalog; its limit is the capacity times the requests per run, none of which is in flight', async () => {
      const plain = await defined({
        name: fresh('llm'),
        type: 'openai-compatible',
        capacity: 2,
        settings: { baseUrl: 'http://127.0.0.1:9/api/v1/' },
      });
      expect(plain.status).toBe(201);
      expect(plain.body).toMatchObject({
        type: 'openai-compatible',
        settings: {
          baseUrl: 'http://127.0.0.1:9/api/v1',
          endpoints: DEFAULT_ENDPOINTS,
          timeouts: { connectMs: 10000, firstByteMs: 900000, idleMs: 300000, quotaWaitMs: 60000 },
          requestsPerRun: 1,
          maxRequestBytes: 33554432,
          maxResponseBytes: 8388608,
          maxDownloadBytes: 268435456,
        },
        secretStatus: 'not_set',
        concurrencyLimit: 2,
        usage: { inFlightRequests: 0 },
      });
      for (const absent of ['organization', 'project', 'headers', 'defaults', 'allowedModels', 'lockedParameters', 'maxValues']) {
        expect([absent, plain.body.settings[absent]]).toEqual([absent, undefined]);
      }
      expect(plain.body.settings.timeouts.totalMs).toBeUndefined();

      const full = {
        baseUrl: 'https://llm.internal/v1',
        organization: 'org-1',
        project: 'proj-2',
        headers: { 'X-Team': 'reports' },
        endpoints: ['audio.speech', 'models.list', 'chat.completions'],
        timeouts: { connectMs: 1000, firstByteMs: 2000, idleMs: 3000, totalMs: 4000, quotaWaitMs: 5000 },
        requestsPerRun: 3,
        defaults: { model: 'small', temperature: 0.2, max_tokens: 512, stop: ['END'], response_format: { type: 'json_object' } },
        allowedModels: ['small', 'large'],
        lockedParameters: ['temperature'],
        maxValues: { max_tokens: 4096 },
      };
      const given = await defined({ name: fresh('llm'), type: 'openai-compatible', capacity: 2, settings: full });
      expect(given.status).toBe(201);
      expect(given.body).toMatchObject({
        settings: { ...full, endpoints: ['chat.completions', 'models.list', 'audio.speech'] },
        concurrencyLimit: 6,
      });
    });

    test('an openai-compatible service says what it refuses: the address, a header, an endpoint, the request defaults, a timeout, a limit, any other field', async () => {
      const service = (settings: Record<string, unknown>) =>
        defined({ name: fresh('llm'), type: 'openai-compatible', capacity: 1, settings });
      const at = { baseUrl: 'http://127.0.0.1:9/v1' };
      for (const baseUrl of ['ftp://llm/v1', 'http://user:pw@llm/v1', 'http://llm/v1?x=1', 'http://llm/v1/../v2', 'llm/v1']) {
        await refused(service({ baseUrl }), 'invalid_base_url');
      }
      await refused(service({}), 'invalid_settings');
      await refused(service({ ...at, apiKey: 'x' }), 'invalid_settings');
      await refused(service({ ...at, headers: { 'X-Api-Key': 'x' } }), 'invalid_header');
      await refused(service({ ...at, headers: { Authorization: 'Bearer x' } }), 'invalid_header');
      await refused(service({ ...at, headers: { 'Content-Type': 'text/plain' } }), 'invalid_header');
      await refused(service({ ...at, headers: { 'X-Team': 'a\nb' } }), 'invalid_header');
      await refused(service({ ...at, organization: 'org\r\nX: y' }), 'invalid_header');
      await refused(service({ ...at, endpoints: [] }), 'invalid_endpoint');
      await refused(service({ ...at, endpoints: ['chat.completions', 'shell.exec'] }), 'invalid_endpoint');
      await refused(service({ ...at, defaults: { messages: [] } }), 'invalid_request_defaults');
      await refused(service({ ...at, defaults: { temperature: 'hot' } }), 'invalid_request_defaults');
      await refused(service({ ...at, defaults: { model: 'large' }, allowedModels: ['small'] }), 'invalid_request_defaults');
      await refused(service({ ...at, defaults: { max_tokens: 9000 }, maxValues: { max_tokens: 4096 } }), 'invalid_request_defaults');
      await refused(service({ ...at, lockedParameters: ['stream'] }), 'invalid_request_defaults');
      await refused(service({ ...at, maxValues: { model: 3 } }), 'invalid_request_defaults');
      await refused(service({ ...at, timeouts: { firstByteMs: 0 } }), 'invalid_timeout');
      await refused(service({ ...at, timeouts: { readMs: 10 } }), 'invalid_timeout');
      await refused(service({ ...at, requestsPerRun: 0 }), 'invalid_limit');
      await refused(service({ ...at, requestsPerRun: 257 }), 'invalid_limit');
      const unlimited = await service({ ...at, timeouts: { totalMs: null } });
      expect([unlimited.status, unlimited.body.settings.timeouts.totalMs]).toEqual([201, undefined]);
    });

    test('the settings are changed as a whole and written out again, which forgets the last check; the alias is changed to another; who changed it is the one who did', async () => {
      const resourceName = fresh('db');
      await defined({ name: resourceName, type: 'jdbc-pool', capacity: 2, settings: database, secretAlias: 'old-pass' });
      await call(root(), 'POST', `${resourcePath(resourceName)}/check`);
      expect((await call(root(), 'GET', resourcePath(resourceName))).body.lastCheck).not.toBeNull();

      const settings = { ...database, host: 'db.internal', connectionsPerRun: 3 };
      const changed = await change(resourceName, { settings });
      expect(changed.status).toBe(200);
      expect(changed.body).toMatchObject({
        settings: { ...settings, port: 5432, maxRows: 10000 },
        secretAlias: 'old-pass',
        concurrencyLimit: 6,
        lastCheck: null,
        updatedBy: root().name,
      });

      const aliased = await change(resourceName, { secretAlias: 'New-Pass' });
      expect(aliased.body).toMatchObject({ secretAlias: 'new-pass', settings: { host: 'db.internal' } });

      const both = await change(resourceName, { capacity: 1, settings: database, secretAlias: 'other-pass' });
      expect(both.body).toMatchObject({ capacity: 1, settings: { host: '127.0.0.1' }, secretAlias: 'other-pass', concurrencyLimit: 1 });
    });

    test('a change that is not valid changes nothing and says why: the settings, the alias, the name or the type', async () => {
      const resourceName = fresh('file');
      const path = `${fresh('dir')}/out.txt`;
      await defined({ name: resourceName, type: 'file', capacity: 1, settings: { path } });
      await refused(change(resourceName, { settings: { path: '../out.txt' } }), 'path_outside_root');
      await refused(change(resourceName, { settings: {} }), 'invalid_settings');
      await refused(change(resourceName, { secretAlias: 'some-key' }), 'invalid_secret_alias');
      await refused(change(resourceName, { name: fresh('file') }), 'immutable_name');
      await refused(change(resourceName, { type: 'file' }), 'immutable_type');
      await refused(change(resourceName, { capacity: 2, type: 'counter' }), 'immutable_type');
      expect((await call(root(), 'GET', resourcePath(resourceName))).body).toMatchObject({
        name: resourceName,
        type: 'file',
        capacity: 1,
        settings: { path },
      });

      const llm = fresh('llm');
      await defined({ name: llm, type: 'openai-compatible', capacity: 1, settings: { baseUrl: 'http://127.0.0.1:9/v1' } });
      await refused(change(llm, { settings: { baseUrl: 'ftp://x' } }), 'invalid_base_url');
      await refused(change(llm, { secretAlias: '-bad' }), 'invalid_secret_alias');
      expect((await call(root(), 'GET', resourcePath(llm))).body).toMatchObject({
        settings: { baseUrl: 'http://127.0.0.1:9/v1' },
        secretAlias: null,
      });
    });
  });

  describe(`secrets with ${name}`, () => {
    test('only an admin: a developer is a 403 forbidden and nobody is a 401, to list and to reload', async () => {
      for (const [method, path] of [
        ['GET', '/api/v1/secrets'],
        ['POST', '/api/v1/secrets/reload'],
      ] as const) {
        const asDeveloper = await call(ada(), method, path);
        expect([path, asDeveloper.status, asDeveloper.body?.error]).toEqual([path, 403, 'forbidden']);
        expect([path, (await call(null, method, path)).status]).toEqual([path, 401]);
      }
    });

    test('the aliases of the keystore are listed in order, each with its type, its status and the resources that use it; a reload says how many there are, and that none changed since the last one', async () => {
      const listed = await call(root(), 'GET', '/api/v1/secrets');
      if (listed.status === 409) {
        // An Engine without a keystore says so to both.
        expect(listed.body.error).toBe('secret_store_not_configured');
        const reload = await call(root(), 'POST', '/api/v1/secrets/reload');
        expect([reload.status, reload.body.error]).toEqual([409, 'secret_store_not_configured']);
        return;
      }
      expect(listed.status).toBe(200);
      const secrets = listed.body.secrets as any[];
      const aliases = secrets.map((secret) => secret.alias);
      expect(aliases).toEqual([...aliases].sort());
      for (const secret of secrets) {
        // A certificate entry also has what may be shown of its certificates (WI-52); a secret not.
        expect(Object.keys(secret).sort()).toEqual(
          secret.type === 'secret'
            ? ['alias', 'status', 'type', 'usedBy']
            : ['alias', 'certificates', 'status', 'type', 'usedBy'],
        );
        expect(secret.alias).toBe(secret.alias.toLowerCase());
        expect(['secret', 'trusted_certificate', 'private_key']).toContain(secret.type);
        expect(['found', 'invalid_secret', 'invalid_key']).toContain(secret.status);
        expect(secret.usedBy).toEqual([...secret.usedBy].sort());
        for (const certificate of secret.certificates ?? []) {
          expect(Object.keys(certificate).sort()).toEqual(['daysLeft', 'expiry', 'fingerprint', 'notAfter', 'subject']);
          expect(certificate.notAfter).toMatch(ISO);
          expect(certificate.fingerprint).toMatch(/^([0-9A-F]{2}:){31}[0-9A-F]{2}$/);
          expect(['valid', 'expiring', 'expired']).toContain(certificate.expiry);
          expect(Number.isInteger(certificate.daysLeft)).toBe(true);
        }
        if (secret.type === 'trusted_certificate') expect(secret.certificates).toHaveLength(1);
      }

      await call(root(), 'POST', '/api/v1/secrets/reload');
      const reload = await call(root(), 'POST', '/api/v1/secrets/reload');
      expect(reload.status).toBe(200);
      expect(reload.body).toEqual({ aliases: secrets.length, changed: [] });
    });

    test('a resource names certificates by aliases of their kind, written in lower case with the status of each; another kind is alias_wrong_type; a plain http service cannot name any', async () => {
      const listed = await call(root(), 'GET', '/api/v1/secrets');
      const secrets = (listed.status === 200 ? listed.body.secrets : []) as any[];
      const trusted = secrets.find((secret) => secret.type === 'trusted_certificate' && secret.status === 'found');
      const secret = secrets.find((entry) => entry.type === 'secret');
      const missing = fresh('Not-There');
      const resourceName = fresh('res');
      const trust = [...(trusted ? [trusted.alias.toUpperCase()] : []), missing];
      const made = await call(root(), 'POST', '/api/v1/resources', {
        name: resourceName,
        capacity: 1,
        type: 'openai-compatible',
        settings: { baseUrl: 'https://127.0.0.1:9/v1', trustAliases: trust, clientCertAlias: missing },
      });
      try {
        expect(made.status).toBe(201);
        expect(made.body.settings.trustAliases).toEqual(trust.map((alias) => alias.toLowerCase()));
        expect(made.body.settings.clientCertAlias).toBe(missing.toLowerCase());
        expect(made.body.trustStatus).toEqual([
          ...(trusted ? [{ alias: trusted.alias, status: 'found' }] : []),
          { alias: missing.toLowerCase(), status: 'missing' },
        ]);
        expect(made.body.clientCertStatus).toBe('missing');
        const checked = await call(root(), 'POST', `/api/v1/resources/${resourceName}/check`);
        expect(checked.body).toMatchObject({ ok: false, failure: 'alias_missing', warnings: [] });
      } finally {
        await call(root(), 'DELETE', `/api/v1/resources/${resourceName}`);
      }

      const plain = await call(root(), 'POST', '/api/v1/resources', {
        name: fresh('res'),
        capacity: 1,
        type: 'openai-compatible',
        settings: { baseUrl: 'http://127.0.0.1:9/v1', trustAliases: [missing] },
      });
      expect([plain.status, plain.body.problem]).toEqual([422, 'invalid_settings']);
      if (secret) {
        const wrong = await call(root(), 'POST', '/api/v1/resources', {
          name: fresh('res'),
          capacity: 1,
          type: 'jdbc-pool',
          settings: { kind: 'postgresql', host: 'db.internal', database: 'app', username: 'app', trustAliases: [secret.alias] },
        });
        expect([wrong.status, wrong.body.problem]).toEqual([422, 'alias_wrong_type']);
      }
      if (trusted) {
        const wrong = await call(root(), 'POST', '/api/v1/resources', {
          name: fresh('res'),
          capacity: 1,
          type: 'openai-compatible',
          settings: { baseUrl: 'http://127.0.0.1:9/v1' },
          secretAlias: trusted.alias,
        });
        expect([wrong.status, wrong.body.problem]).toEqual([422, 'alias_wrong_type']);
      }
    });

    test('a resource that refers to an alias the keystore does not have keeps the alias in lower case, says it is missing, and its check fails as alias_missing without asking the service', async () => {
      const resourceName = fresh('res');
      const alias = `${fresh('Key')}`.toUpperCase();
      const made = await call(root(), 'POST', '/api/v1/resources', {
        name: resourceName,
        capacity: 1,
        type: 'openai-compatible',
        settings: { baseUrl: 'http://127.0.0.1:9/v1' },
        secretAlias: alias,
      });
      try {
        expect(made.status).toBe(201);
        expect(made.body).toMatchObject({
          type: 'openai-compatible',
          secretAlias: alias.toLowerCase(),
          secretStatus: 'missing',
        });
        expect(made.body.settings.baseUrl).toBe('http://127.0.0.1:9/v1');
        const checked = await call(root(), 'POST', `/api/v1/resources/${resourceName}/check`);
        expect(checked.body).toMatchObject({ ok: false, failure: 'alias_missing' });
      } finally {
        await call(root(), 'DELETE', `/api/v1/resources/${resourceName}`);
      }
    });
  });
}
