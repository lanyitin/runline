import { afterEach, describe, expect, test } from 'vitest';
import { createTestApp, TEST_TOKEN, type TestApp } from '../../test-support/app';
import { demoJars } from '../../test-support/fake-jars';
import { ApiFailure } from './failure';

// A version is a content hash and an uploader (ADR-020): what the Console reads of it and what it
// sends to name one, held against the Fake Engine (which the contract tests hold to the real one).

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };
const ada = { name: 'ada', role: 'developer' as const };
const HASH = 'ab'.repeat(32);
const start = async (identity: { name: string; role: 'admin' | 'developer' }) => {
  app = await createTestApp({ identity });
  return app.context.api;
};
const pipeline = { name: 'tool', className: 'x.Tool' };
/** The same content, uploaded by two people. */
const sharedBy = (...people: string[]) =>
  people.forEach((who) =>
    app.engine.backend.seedArtifact(who, [pipeline], { contentHash: HASH }),
  );

describe('a version read', () => {
  test('says whose it is, and so does each pipeline in the listing', async () => {
    const api = await start(ada);
    sharedBy('ada');

    expect((await api.artifact(HASH)).uploader).toBe('ada');
    expect((await api.definitions()).definitions.map((d) => d.uploader)).toEqual(['ada']);
  });

  test('is named by the uploader when an admin can see several: without it the Engine says ambiguous_version', async () => {
    const api = await start(root);
    sharedBy('ada', 'bob');

    const failure = await api.artifact(HASH).catch((e) => e);

    expect(failure).toBeInstanceOf(ApiFailure);
    expect(failure).toMatchObject({
      status: 409,
      body: { error: 'ambiguous_version', uploaders: ['ada', 'bob'] },
    });
    expect((await api.artifact(HASH, 'bob')).uploader).toBe('bob');
    expect((await api.definitions()).definitions.map((d) => d.uploader)).toEqual(['ada', 'bob']);
  });

  test("a developer who asks for another's version is told it is not there", async () => {
    const api = await start(ada);
    sharedBy('ada', 'bob');

    await expect(api.artifact(HASH, 'bob')).rejects.toMatchObject({ status: 404 });
    expect((await api.artifact(HASH, 'ada')).uploader).toBe('ada');
  });

  test('an uploader goes into the address as one piece, whatever it holds', async () => {
    const api = await start(root);
    app.engine.backend.seedArtifact('a&b=c', [pipeline], { contentHash: HASH });

    expect((await api.artifact(HASH, 'a&b=c')).uploader).toBe('a&b=c');
  });
});

describe('a run of a version', () => {
  test('is made of the version that is named, and says whose it is', async () => {
    const api = await start(root);
    app.engine.backend.autoRun = false;
    sharedBy('ada', 'bob');

    const ambiguous = await api.createRun({ contentHash: HASH, pipeline: 'tool' }).catch((e) => e);
    const run = await api.createRun({ contentHash: HASH, uploader: 'bob', pipeline: 'tool' });

    expect(ambiguous).toMatchObject({ status: 409, body: { error: 'ambiguous_version' } });
    expect(run.uploader).toBe('bob');
    expect(app.engine.backend.runs).toHaveLength(1);
  });

  test("a developer's request for another's version is a 404 definition_not_found", async () => {
    const api = await start(ada);
    sharedBy('ada', 'bob');

    await expect(
      api.createRun({ contentHash: HASH, uploader: 'bob', pipeline: 'tool' }),
    ).rejects.toMatchObject({ status: 404, body: { error: 'definition_not_found' } });
  });
});

describe('what an admin does to one version', () => {
  test('the unsafe setting is of the version named, and the answer says whose', async () => {
    const api = await start(root);
    app.engine.backend.seedArtifact('ada', [{ ...pipeline, networkUnrestricted: true }], {
      contentHash: HASH,
    });
    app.engine.backend.seedArtifact('bob', [{ ...pipeline, networkUnrestricted: true }], {
      contentHash: HASH,
    });

    const ambiguous = await api.setUnsafeExecution(HASH, 'tool', true).catch((e) => e);
    const set = await api.setUnsafeExecution(HASH, 'tool', true, 'ada');

    expect(ambiguous).toMatchObject({ status: 409, body: { error: 'ambiguous_version' } });
    expect(set).toMatchObject({ uploader: 'ada', allow: true, setBy: 'root' });
    expect(app.engine.backend.definitionOf(HASH, 'ada', 'tool')!.allowUnsafeExecution).toBe(true);
    expect(app.engine.backend.definitionOf(HASH, 'bob', 'tool')!.allowUnsafeExecution).toBe(false);
  });

  test("deleting takes the version named and leaves the other uploader's", async () => {
    const api = await start(root);
    sharedBy('ada', 'bob');

    await expect(api.deleteVersion(HASH)).rejects.toMatchObject({
      status: 409,
      body: { error: 'ambiguous_version' },
    });
    await api.deleteVersion(HASH, 'ada');

    expect(app.engine.backend.version(HASH, 'ada')).toBeUndefined();
    expect(app.engine.backend.version(HASH, 'bob')).toBeDefined();
  });

  test('a trigger is bound to the version named, shows it, and moves only when told to', async () => {
    const api = await start(root);
    sharedBy('ada', 'bob');

    const ambiguous = await api
      .createTrigger({ name: 't', kind: 'cron', contentHash: HASH, pipeline: 'tool', cron: '* * * * *' })
      .catch((e) => e);
    const made = await api.createTrigger({
      name: 't',
      kind: 'cron',
      contentHash: HASH,
      uploader: 'bob',
      pipeline: 'tool',
      cron: '* * * * *',
    });
    const disabled = await api.updateTrigger('t', { enabled: false });
    const moved = await api.updateTrigger('t', { contentHash: HASH, uploader: 'ada' });

    expect(ambiguous).toMatchObject({ status: 409, body: { error: 'ambiguous_version' } });
    expect(made.trigger.uploader).toBe('bob');
    expect(disabled.uploader).toBe('bob');
    expect(moved.uploader).toBe('ada');
  });

  test('a change of the allow-list says which uploader each changed verdict is of', async () => {
    const api = await start(root);
    app.engine.backend.seedArtifact('ada', [{ ...pipeline, references: ['java.io.PrintStream'] }], { contentHash: HASH });
    app.engine.backend.seedArtifact('bob', [{ ...pipeline, references: ['java.io.PrintStream'] }], { contentHash: HASH });

    const preview = await api.removeEntry('class', 'java.io.PrintStream', { preview: true });

    expect(preview.impact.changes.map((c) => [c.contentHash, c.uploader])).toEqual([
      [HASH, 'ada'],
      [HASH, 'bob'],
    ]);
  });
});

describe('an upload', () => {
  test('of bytes another person has uploaded is a new version of the uploader', async () => {
    const api = await start(ada);
    app.engine.backend.seedArtifact('bob', [{ name: 'demo-slow', className: 'x.S' }], {
      contentHash: (await import('node:crypto'))
        .createHash('sha256')
        .update(demoJars().slow)
        .digest('hex'),
    });

    const result = await api.uploadJar(new File([demoJars().slow as BlobPart], 'p.jar'));

    expect(result.created).toBe(true);
    expect(result.artifact.uploader).toBe('ada');
  });
});

describe('what deleting a version would do (ADR-047)', () => {
  test('the preview counts the runs by state, their range, their log lines, the triggers and whether the jar goes', async () => {
    const api = await start(root);
    sharedBy('ada');
    const backend = app.engine.backend;
    const done = backend.seedRun('ada', {
      contentHash: HASH,
      pipeline: 'tool',
      state: 'SUCCEEDED',
      createdAt: '2026-01-02T03:04:05Z',
    });
    backend.write(done.runId, 'a', 'b');
    backend.seedRun('ada', {
      contentHash: HASH,
      pipeline: 'tool',
      state: 'RUNNING',
      createdAt: '2026-02-02T03:04:05Z',
    });
    backend.triggers.seed({ name: 'nightly', contentHash: HASH, uploader: 'ada', pipeline: 'tool' });

    const impact = await api.deletionImpact(HASH, 'ada');

    expect(impact).toEqual({
      runs: {
        total: 2,
        byState: { SUCCEEDED: 1, RUNNING: 1 },
        active: 1,
        earliestAt: '2026-01-02T03:04:05Z',
        latestAt: '2026-02-02T03:04:05Z',
      },
      logEntries: 2,
      triggers: [{ name: 'nightly', kind: 'cron' }],
      contentRemoved: true,
      deletable: false,
    });
    expect(backend.version(HASH, 'ada')).toBeDefined();
  });

  test('a version without runs has no range and is deletable; its jar stays while another uploader has it', async () => {
    const api = await start(root);
    sharedBy('ada', 'bob');

    const impact = await api.deletionImpact(HASH, 'ada');

    expect(impact.runs).toEqual({ total: 0, byState: {}, active: 0, earliestAt: null, latestAt: null });
    expect(impact).toMatchObject({ logEntries: 0, triggers: [], contentRemoved: false, deletable: true });
  });

  test('a version that is not named when several uploaders have it is ambiguous; a missing one is a 404', async () => {
    const api = await start(root);
    sharedBy('ada', 'bob');

    await expect(api.deletionImpact(HASH)).rejects.toMatchObject({
      status: 409,
      body: { error: 'ambiguous_version' },
    });
    await expect(api.deletionImpact('f'.repeat(64))).rejects.toMatchObject({ status: 404 });
  });

  test('ended runs are deleted with the version when their number is confirmed; the firings keep, without the run', async () => {
    const api = await start(root);
    sharedBy('ada');
    const backend = app.engine.backend;
    const run = backend.seedRun('ada', { contentHash: HASH, pipeline: 'tool', state: 'FAILED' });
    backend.seedRun('ada', { contentHash: HASH, pipeline: 'tool', state: 'SUCCEEDED' });
    backend.triggers.firings.push({
      triggerName: 'gone',
      firedAt: '2026-01-01T00:00:00Z',
      scheduledFor: null,
      deliveryId: null,
      outcome: 'run_created',
      reason: null,
      detail: null,
      runId: run.runId,
    });

    await expect(api.deleteVersion(HASH, 'ada')).rejects.toMatchObject({
      status: 409,
      body: { error: 'runs_exist', total: 2 },
    });
    await expect(api.deleteVersion(HASH, 'ada', 1)).rejects.toMatchObject({
      status: 409,
      body: { error: 'impact_changed', total: 2 },
    });
    expect(backend.version(HASH, 'ada')).toBeDefined();
    await api.deleteVersion(HASH, 'ada', 2);

    expect(backend.version(HASH, 'ada')).toBeUndefined();
    expect(backend.runs.filter((r) => r.contentHash === HASH)).toEqual([]);
    expect(backend.triggers.firings.map((f) => f.runId)).toEqual([null]);
  });

  test('a trigger is in_use with the triggers; an active run is runs_active with the count; the trigger is judged first', async () => {
    const api = await start(root);
    sharedBy('ada');
    const backend = app.engine.backend;
    backend.seedRun('ada', { contentHash: HASH, pipeline: 'tool', state: 'QUEUED' });
    backend.seedRun('ada', { contentHash: HASH, pipeline: 'tool', state: 'TIMED_OUT_UNFINISHED' });

    await expect(api.deleteVersion(HASH, 'ada', 2)).rejects.toMatchObject({
      status: 409,
      body: { error: 'runs_active', active: 2 },
    });
    backend.triggers.seed({ name: 'nightly', contentHash: HASH, uploader: 'ada', pipeline: 'tool' });
    await expect(api.deleteVersion(HASH, 'ada', 2)).rejects.toMatchObject({
      status: 409,
      body: { error: 'in_use', triggers: [{ name: 'nightly', kind: 'cron' }] },
    });
    expect(backend.version(HASH, 'ada')).toBeDefined();
  });

  test('a version without runs is deleted without a count, and with a count of 0', async () => {
    const api = await start(root);
    sharedBy('ada');
    await api.deleteVersion(HASH, 'ada', 0);
    expect(app.engine.backend.version(HASH, 'ada')).toBeUndefined();
  });

  test('a count that is not a whole number is a 400 and deletes nothing', async () => {
    await start(root);
    sharedBy('ada');
    for (const runs of ['x', '-1', '1.5']) {
      const answer = await fetch(`${app.engine.url}/api/v1/artifacts/${HASH}?uploader=ada&runs=${runs}`, {
        method: 'DELETE',
        headers: { Authorization: `Bearer ${TEST_TOKEN}` },
      });
      expect(answer.status).toBe(400);
    }
    expect(app.engine.backend.version(HASH, 'ada')).toBeDefined();
  });
});
