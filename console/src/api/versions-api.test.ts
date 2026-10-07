import { afterEach, describe, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
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
