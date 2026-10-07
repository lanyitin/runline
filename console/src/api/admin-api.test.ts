import { afterEach, describe, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { demoJars } from '../../test-support/fake-jars';
import { ApiFailure } from './failure';

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };
const ada = { name: 'ada', role: 'developer' as const };
const start = async (identity: { name: string; role: 'admin' | 'developer' } = root) => {
  app = await createTestApp({ identity });
  return app.context.api;
};
const asFile = (bytes: Uint8Array) => new File([bytes as BlobPart], 'pipeline.jar');
const slow = async (api: Awaited<ReturnType<typeof start>>) =>
  (await api.uploadJar(asFile(demoJars().slow))).artifact.contentHash;

describe('triggers', () => {
  test('a cron trigger is made and read back with every field of it, and has no secret', async () => {
    const api = await start();
    const contentHash = await slow(api);

    const made = await api.createTrigger({
      name: 'nightly',
      kind: 'cron',
      contentHash,
      pipeline: 'demo-slow',
      cron: '0 3 * * *',
      timeZone: 'Asia/Taipei',
      parameters: { steps: '2' },
    });

    expect(made.secret).toBeNull();
    expect(made.trigger).toMatchObject({
      name: 'nightly',
      kind: 'cron',
      contentHash,
      pipeline: 'demo-slow',
      parameters: { steps: '2' },
      effectiveParameters: { label: 'demo', steps: '2', delayMillis: '1000' },
      enabled: true,
      cron: '0 3 * * *',
      timeZone: 'Asia/Taipei',
      webhookPath: null,
      secretConfigured: false,
      secretRotatedAt: null,
      createdBy: 'root',
      updatedBy: 'root',
    });
    expect(await api.trigger('nightly')).toEqual(made.trigger);
    expect((await api.triggers()).map((t) => t.name)).toEqual(['nightly']);
  });

  test('a webhook trigger gives its secret in the answer that makes it, once', async () => {
    const api = await start();
    const contentHash = await slow(api);

    const made = await api.createTrigger({ name: 'hook', kind: 'webhook', contentHash, pipeline: 'demo-slow' });

    expect(typeof made.secret).toBe('string');
    expect(made.trigger).toMatchObject({
      kind: 'webhook',
      webhookPath: '/api/v1/webhooks/hook',
      secretConfigured: true,
    });
    expect(JSON.stringify(await api.trigger('hook'))).not.toContain(made.secret!);
    expect(JSON.stringify(await api.triggers())).not.toContain(made.secret!);
  });

  test('what is wrong with a trigger is a failure with the problem in its body', async () => {
    const api = await start();
    const contentHash = await slow(api);

    const failure = await api
      .createTrigger({ name: 'x', kind: 'cron', contentHash, pipeline: 'demo-slow', cron: 'nope' })
      .catch((e) => e);

    expect(failure).toBeInstanceOf(ApiFailure);
    expect(failure).toMatchObject({
      status: 422,
      body: { error: 'invalid_trigger', problem: 'cron_expression' },
    });
  });

  test('a trigger is changed in the parts that are given', async () => {
    const api = await start();
    const contentHash = await slow(api);
    await api.createTrigger({ name: 'nightly', kind: 'cron', contentHash, pipeline: 'demo-slow', cron: '0 3 * * *' });

    const changed = await api.updateTrigger('nightly', { enabled: false, cron: '*/10 * * * *' });

    expect(changed).toMatchObject({ enabled: false, cron: '*/10 * * * *', timeZone: 'UTC' });
  });

  test('the secret is rotated and the new one is given once; a cron trigger has none: 409 not_a_webhook', async () => {
    const api = await start();
    const contentHash = await slow(api);
    const first = (await api.createTrigger({ name: 'hook', kind: 'webhook', contentHash, pipeline: 'demo-slow' })).secret;
    await api.createTrigger({ name: 'cr', kind: 'cron', contentHash, pipeline: 'demo-slow', cron: '* * * * *' });

    const rotated = await api.rotateSecret('hook');

    expect(rotated.secret).not.toBe(first);
    expect(typeof rotated.secret).toBe('string');
    expect(rotated.trigger.secretConfigured).toBe(true);
    await expect(api.rotateSecret('cr')).rejects.toMatchObject({ status: 409, body: { error: 'not_a_webhook' } });
  });

  test('a trigger is deleted: it is gone afterwards', async () => {
    const api = await start();
    const contentHash = await slow(api);
    await api.createTrigger({ name: 'hook', kind: 'webhook', contentHash, pipeline: 'demo-slow' });

    await api.deleteTrigger('hook');

    await expect(api.trigger('hook')).rejects.toMatchObject({ status: 404, body: { error: 'trigger_not_found' } });
  });

  test('a name goes into the address as one piece, whatever it holds', async () => {
    const api = await start();
    const contentHash = await slow(api);
    await api.createTrigger({ name: 'a.b-c_d', kind: 'webhook', contentHash, pipeline: 'demo-slow' });

    expect((await api.trigger('a.b-c_d')).name).toBe('a.b-c_d');
    await expect(api.trigger('a/../b')).rejects.toMatchObject({ status: 404 });
  });

  test('the firings come newest first, each with its outcome, reason, detail and run', async () => {
    const api = await start();
    const contentHash = await slow(api);
    await api.createTrigger({ name: 'hook', kind: 'webhook', contentHash, pipeline: 'demo-slow' });
    const triggers = app.engine.backend.triggers;
    triggers.firings.push(
      {
        triggerName: 'hook',
        firedAt: '2026-10-05T01:00:00Z',
        scheduledFor: null,
        deliveryId: 'd-1',
        outcome: 'run_created',
        reason: null,
        detail: null,
        runId: '11111111-1111-4111-8111-111111111111',
      },
      {
        triggerName: 'hook',
        firedAt: '2026-10-05T02:00:00Z',
        scheduledFor: '2026-10-05T02:00:00Z',
        deliveryId: null,
        outcome: 'refused',
        reason: 'unsafe_not_allowed',
        detail: 'it is unsafe',
        runId: null,
      },
    );

    const firings = await api.firings('hook');

    expect(firings).toEqual([
      {
        firedAt: '2026-10-05T02:00:00Z',
        scheduledFor: '2026-10-05T02:00:00Z',
        deliveryId: null,
        outcome: 'refused',
        reason: 'unsafe_not_allowed',
        detail: 'it is unsafe',
        runId: null,
      },
      {
        firedAt: '2026-10-05T01:00:00Z',
        scheduledFor: null,
        deliveryId: 'd-1',
        outcome: 'run_created',
        reason: null,
        detail: null,
        runId: '11111111-1111-4111-8111-111111111111',
      },
    ]);
    expect(await api.firings('hook', 1)).toHaveLength(1);
  });

  test('a developer is refused: 403 forbidden', async () => {
    const api = await start(ada);
    await expect(api.triggers()).rejects.toMatchObject({ status: 403, body: { error: 'forbidden' } });
  });

  test('an answer that is not what 08-api.md says is a failure, not a broken page', async () => {
    const api = await start();
    app.engine.faults.push({ match: /GET \/api\/v1\/triggers/, status: 200, body: { triggers: [{ name: 7 }] }, times: 1 });
    await expect(api.triggers()).rejects.toMatchObject({ status: 0 });
  });
});

describe('the allow-list', () => {
  test('is read: its version, who changed it, the entries and what the verdicts do not cover', async () => {
    const api = await start();

    const list = await api.allowList();

    expect(list.version).toBe('1');
    expect(list.changedBy).toBe('system');
    expect(list.entries).toContainEqual(
      expect.objectContaining({ kind: 'class', name: 'java.io.PrintStream', exactOnly: null }),
    );
    expect(list.entries).toContainEqual(
      expect.objectContaining({ kind: 'package', name: 'java.lang', exactOnly: true }),
    );
    expect(typeof list.limitations).toBe('string');
  });

  test('has a history, newest first', async () => {
    const api = await start();
    await api.addEntry({ kind: 'class', name: 'a.B' });

    const versions = await api.allowListVersions();

    expect(versions.map((v) => [v.version, v.action, v.changedBy])).toEqual([
      ['2', 'ENTRY_ADDED', 'root'],
      ['1', 'INITIAL', 'system'],
    ]);
    expect(await api.allowListVersions(1)).toHaveLength(1);
  });

  test('a preview says what would change and changes nothing; applying does it and gives the new version', async () => {
    const api = await start();
    const contentHash = await slow(api);

    const preview = await api.removeEntry('class', 'java.io.PrintStream', { preview: true });

    expect(preview).toMatchObject({ preview: true, version: '1', entry: null });
    expect(preview.impact.becameUnsafe).toBe(1);
    expect(preview.impact.changes).toEqual([
      {
        contentHash,
        uploader: 'root',
        pipeline: 'demo-slow',
        className: 'samples.slow.SlowPipeline',
        from: 'SAFE',
        to: 'UNSAFE',
        allowUnsafeExecution: false,
        unsafeExecutionRevoked: false,
      },
    ]);
    expect((await api.allowList()).version).toBe('1');

    const applied = await api.removeEntry('class', 'java.io.PrintStream');

    expect(applied).toMatchObject({ preview: false, version: '2', entry: null });
    expect(applied.impact.becameUnsafe).toBe(1);
    expect((await api.allowList()).entries.map((e) => e.name)).not.toContain('java.io.PrintStream');
  });

  test('an entry that is added is given back with who made it; a redundant entry is named', async () => {
    const api = await start();
    await api.addEntry({ kind: 'class', name: 'acme.sub.Thing' });

    const added = await api.addEntry({ kind: 'package', name: 'acme', exactOnly: false });

    expect(added.entry).toMatchObject({ kind: 'package', name: 'acme', exactOnly: false, createdBy: 'root' });
    expect(added.redundantEntries.map((e) => e.name)).toEqual(['acme.sub.Thing']);
  });

  test('an entry is changed', async () => {
    const api = await start();
    await api.addEntry({ kind: 'package', name: 'acme', exactOnly: false });

    const changed = await api.modifyEntry('package', 'acme', { exactOnly: true });

    expect(changed.entry).toMatchObject({ name: 'acme', exactOnly: true, updatedBy: 'root' });
    expect(await api.modifyEntry('package', 'acme', { name: 'acme2' }).then((c) => c.entry?.name)).toBe('acme2');
  });

  test('a refusal says why: entry_exists with the entry, entry_covered with the entry that covers, invalid_entry with the problem', async () => {
    const api = await start();

    await expect(api.addEntry({ kind: 'package', name: 'java.lang' })).rejects.toMatchObject({
      status: 409,
      body: { error: 'entry_exists', existing: { name: 'java.lang' } },
    });
    await expect(api.addEntry({ kind: 'class', name: 'java.lang.invoke.Thing' })).rejects.toMatchObject({
      status: 409,
      body: { error: 'entry_covered', coveredBy: { name: 'java.lang.invoke' } },
    });
    await expect(api.addEntry({ kind: 'class', name: 'NoPackage' })).rejects.toMatchObject({
      status: 422,
      body: { error: 'invalid_entry', problem: 'name' },
    });
  });

  test('a name goes into the address as one piece: an entry with a dollar sign in it is found', async () => {
    const api = await start();
    await api.addEntry({ kind: 'class', name: 'acme.Outer$Inner' });

    await expect(api.removeEntry('class', 'acme.Outer$Inner')).resolves.toMatchObject({ preview: false });
  });

  test('a recheck is previewed and applied', async () => {
    const api = await start();

    expect(await api.recheck({ preview: true })).toMatchObject({ preview: true, version: '1' });
    expect(await api.recheck()).toMatchObject({ preview: false, version: '1' });
  });

  test('a developer is refused: 403 forbidden', async () => {
    const api = await start(ada);
    await expect(api.allowList()).rejects.toMatchObject({ status: 403, body: { error: 'forbidden' } });
  });
});

describe('shared resources', () => {
  test('a resource is defined and listed with its capacity, who made it, who holds it and who waits', async () => {
    const api = await start();

    const made = await api.createResource({ name: 'printer', capacity: 2 });

    expect(made).toMatchObject({ name: 'printer', capacity: 2, enabled: true, createdBy: 'root', holders: [], waiters: [] });
    expect(await api.resources()).toEqual([made]);
  });

  test('a resource is changed: capacity and enabled', async () => {
    const api = await start();
    await api.createResource({ name: 'printer', capacity: 1 });

    const changed = await api.updateResource('printer', { capacity: 3, enabled: false });

    expect(changed).toMatchObject({ capacity: 3, enabled: false, updatedBy: 'root' });
  });

  test('holders and waiters have their times; a holder is released and the answer says which run', async () => {
    const api = await start();
    app.engine.backend.defineResource('printer');
    const first = app.engine.backend.seedRun('ada', { state: 'RUNNING', pipeline: 'r' });
    const second = app.engine.backend.seedRun('ada', { state: 'WAITING_FOR_RESOURCES', pipeline: 'r' });
    app.engine.backend.seedHolder('printer', first.runId, 'r');
    app.engine.backend.seedWaiter('printer', second.runId, 'r');

    const [printer] = await api.resources();

    expect(printer.holders).toEqual([
      { runId: first.runId, pipeline: 'r', heldSince: expect.any(String), heldSeconds: expect.any(Number) },
    ]);
    expect(printer.waiters).toEqual([
      {
        runId: second.runId,
        pipeline: 'r',
        waitingFor: ['printer'],
        waitingSince: expect.any(String),
        waitedSeconds: expect.any(Number),
      },
    ]);
    const released = await api.releaseHolder('printer', first.runId);
    expect(released).toMatchObject({ resource: 'printer', runId: first.runId, pipeline: 'r' });
    await expect(api.releaseHolder('printer', first.runId)).rejects.toMatchObject({
      status: 404,
      body: { error: 'not_a_holder' },
    });
  });

  test('a refusal is a failure with its code: resource_exists, invalid_resource, resource_not_found', async () => {
    const api = await start();
    await api.createResource({ name: 'printer', capacity: 1 });

    await expect(api.createResource({ name: 'printer', capacity: 1 })).rejects.toMatchObject({ status: 409, body: { error: 'resource_exists' } });
    await expect(api.createResource({ name: 'p2', capacity: 0 })).rejects.toMatchObject({ status: 422, body: { error: 'invalid_resource' } });
    await expect(api.updateResource('nope', { capacity: 2 })).rejects.toMatchObject({ status: 404, body: { error: 'resource_not_found' } });
  });
});

describe('typed resources, checks, deleting and secrets', () => {
  test('a resource has its type, settings, secret alias and status, last check and the definitions that declare it', async () => {
    const api = await start();
    app.engine.backend.secrets.configure([{ alias: 'llm-key', type: 'secret', status: 'found', fingerprint: 'a' }]);
    app.engine.backend.resources.define('llm', {
      type: 'openai-compatible',
      settings: { baseUrl: 'http://llm.internal:8000/v1' },
      secretAlias: 'llm-key',
      lastCheck: { ok: false, failure: 'connection_failed', checkedAt: '2026-10-07T01:02:03Z' },
    });
    const { artifact } = await api.uploadJar(asFile(demoJars().resource));
    app.engine.backend.defineResource('demo-printer');

    const byName = Object.fromEntries((await api.resources()).map((r) => [r.name, r]));

    expect(byName.llm).toMatchObject({
      type: 'openai-compatible',
      settings: { baseUrl: 'http://llm.internal:8000/v1' },
      secretAlias: 'llm-key',
      secretStatus: 'found',
      lastCheck: { ok: false, failure: 'connection_failed', checkedAt: '2026-10-07T01:02:03Z' },
      declaredBy: { count: 0, triggers: 0, definitions: [] },
    });
    expect(byName['demo-printer']).toMatchObject({
      type: 'counter',
      settings: {},
      secretAlias: null,
      secretStatus: 'not_set',
      lastCheck: null,
      declaredBy: {
        count: 1,
        triggers: 0,
        definitions: [
          { contentHash: artifact.contentHash, uploader: 'root', pipeline: 'demo-resource', declaredType: null, triggers: 0 },
        ],
      },
    });
  });

  test('a check gives its result, which is the last check of the resource from then on', async () => {
    const api = await start();
    app.engine.backend.resources.define('share', { type: 'file', settings: { path: 'out.txt' }, entityFailure: 'root_unavailable' });

    const result = await api.checkResource('share');

    expect(result).toEqual({ ok: false, failure: 'root_unavailable', checkedAt: expect.any(String) });
    expect((await api.resources())[0].lastCheck).toEqual(result);
  });

  test('deleting is previewed, then done; a resource in use is a 409 resource_in_use with the counts', async () => {
    const api = await start();
    app.engine.backend.defineResource('printer');
    app.engine.backend.defineResource('scanner');
    const run = app.engine.backend.seedRun('ada', { state: 'RUNNING', pipeline: 'r' });
    app.engine.backend.seedHolder('scanner', run.runId, 'r');

    expect(await api.deleteResource('printer', { preview: true })).toEqual({
      resource: 'printer',
      definitions: 0,
      triggers: 0,
      holders: 0,
      waiters: 0,
      inUse: false,
    });
    expect(await api.deleteResource('printer')).toBeNull();
    expect((await api.resources()).map((r) => r.name)).toEqual(['scanner']);
    await expect(api.deleteResource('scanner')).rejects.toMatchObject({
      status: 409,
      body: { error: 'resource_in_use', holders: 1, waiters: 0 },
    });
  });

  test('the secrets are listed with their type, status and users; a reload says how many and what changed', async () => {
    const api = await start();
    const secrets = app.engine.backend.secrets;
    secrets.configure([{ alias: 'llm-key', type: 'secret', status: 'found', fingerprint: 'a' }]);
    app.engine.backend.resources.define('llm', { type: 'openai-compatible', settings: { baseUrl: 'http://x/v1' }, secretAlias: 'llm-key' });

    expect(await api.secrets()).toEqual([{ alias: 'llm-key', type: 'secret', status: 'found', usedBy: ['llm'] }]);

    secrets.writeFile({
      entries: [
        { alias: 'llm-key', type: 'secret', status: 'found', fingerprint: 'b' },
        { alias: 'ca', type: 'trusted_certificate', status: 'found', fingerprint: 'c' },
      ],
    });
    expect(await api.reloadSecrets()).toEqual({
      aliases: 2,
      changed: [
        { alias: 'ca', usedBy: [] },
        { alias: 'llm-key', usedBy: ['llm'] },
      ],
    });
  });

  test('a keystore that is not configured or cannot be read is a failure with its code and problem', async () => {
    const api = await start();
    await expect(api.secrets()).rejects.toMatchObject({ status: 409, body: { error: 'secret_store_not_configured' } });
    app.engine.backend.secrets.configure([]);
    app.engine.backend.secrets.writeFile({ problem: 'wrong_password' });
    await expect(api.reloadSecrets()).rejects.toMatchObject({
      status: 422,
      body: { error: 'secret_store_unreadable', problem: 'wrong_password' },
    });
  });
});

describe('unsafe execution and deleting a version', () => {
  test('unsafe execution is allowed for one pipeline of one version, and the answer says who and when', async () => {
    const api = await start();
    const { artifact } = await api.uploadJar(asFile(demoJars().unsafe));

    const allowed = await api.setUnsafeExecution(artifact.contentHash, 'demo-unsafe', true);

    expect(allowed).toMatchObject({ contentHash: artifact.contentHash, pipeline: 'demo-unsafe', allow: true, setBy: 'root' });
    expect((await api.artifact(artifact.contentHash)).pipelines[0].allowUnsafeExecution).toBe(true);
    expect((await api.setUnsafeExecution(artifact.contentHash, 'demo-unsafe', false)).allow).toBe(false);
    await expect(api.setUnsafeExecution(artifact.contentHash, 'nope', true)).rejects.toMatchObject({
      status: 404,
      body: { error: 'definition_not_found' },
    });
  });

  test('a pipeline whose name has a dot goes into the address as one piece', async () => {
    const api = await start();
    const hash = app.engine.backend.seedArtifact('ada', [{ name: 'a.b', className: 'x.AB' }]);

    expect((await api.setUnsafeExecution(hash, 'a.b', true)).pipeline).toBe('a.b');
  });

  test('a version is deleted; one that is in use is a 409 in_use', async () => {
    const api = await start();
    const free = await slow(api);
    const used = app.engine.backend.seedArtifact('ada', [{ name: 'u', className: 'x.U' }]);
    app.engine.backend.seedRun('ada', { contentHash: used, pipeline: 'u' });

    await api.deleteVersion(free);
    await expect(api.artifact(free)).rejects.toMatchObject({ status: 404 });
    await expect(api.deleteVersion(used)).rejects.toMatchObject({ status: 409, body: { error: 'in_use' } });
  });
});
