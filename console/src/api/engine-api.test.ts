import { afterEach, describe, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { demoJars, fakeJar } from '../../test-support/fake-jars';
import { ApiFailure } from './failure';

let app: TestApp;
afterEach(() => app.dispose());

const ada = { name: 'ada', role: 'developer' as const };
const start = async (options: Parameters<typeof createTestApp>[0] = { identity: ada }) => {
  app = await createTestApp(options);
  return app.context.api;
};
const asFile = (bytes: Uint8Array) => new File([bytes as BlobPart], 'pipeline.jar');

describe('uploading a jar', () => {
  test('a new version is created: the version with its pipelines, parameters, verdict and who uploaded', async () => {
    const api = await start();

    const result = await api.uploadJar(asFile(demoJars().slow));

    expect(result.created).toBe(true);
    expect(result.artifact).toMatchObject({ uploadedBy: 'ada', sizeBytes: demoJars().slow.length });
    expect(result.artifact.contentHash).toMatch(/^[0-9a-f]{64}$/);
    expect(result.artifact.pipelines).toHaveLength(1);
    expect(result.artifact.pipelines[0]).toMatchObject({
      name: 'demo-slow',
      className: 'samples.slow.SlowPipeline',
      verdict: 'SAFE',
      reasons: [],
      warnings: [],
      allowUnsafeExecution: false,
    });
    expect(result.artifact.pipelines[0].metadata.parameters).toEqual([
      { name: 'label', required: false, default: 'demo' },
      { name: 'steps', required: false, default: '30' },
      { name: 'delayMillis', required: false, default: '1000' },
    ]);
  });

  test('the same bytes again are not created again: the version that was there', async () => {
    const api = await start();
    const first = await api.uploadJar(asFile(demoJars().slow));

    const again = await api.uploadJar(asFile(demoJars().slow));

    expect(again.created).toBe(false);
    expect(again.artifact.contentHash).toBe(first.artifact.contentHash);
  });

  test('an unsafe pipeline has its reasons, each with the path from the pipeline', async () => {
    const api = await start();
    const { artifact } = await api.uploadJar(asFile(demoJars().unsafe));
    const [pipeline] = artifact.pipelines;

    expect(pipeline.verdict).toBe('UNSAFE');
    expect(pipeline.reasons).toContainEqual({
      kind: 'NOT_ALLOW_LISTED',
      category: null,
      className: 'java.io.File',
      member: null,
      path: ['samples.unsafe.UnsafePipeline', 'java.io.File'],
      detail: null,
    });
  });

  test.each([
    ['junk', 422, 'not_a_jar'],
    ['noPipeline', 422, 'no_pipeline_found'],
  ] as const)(
    'a refusal (%s) is a failure with the status and the body of the Engine',
    async (which, status, error) => {
      const api = await start();

      const failure = await api.uploadJar(asFile(demoJars()[which])).catch((e) => e);

      expect(failure).toBeInstanceOf(ApiFailure);
      expect(failure).toMatchObject({ status, body: { error } });
    },
  );

  test('a file over the limit is a 413 failure', async () => {
    const api = await start();
    app.engine.backend.maxUploadBytes = 100;

    const failure = await api.uploadJar(asFile(demoJars().slow)).catch((e) => e);

    expect(failure).toMatchObject({ status: 413, body: { error: 'too_large' } });
  });

  test('tells the progress and can be cancelled', async () => {
    const api = await start();
    const told: number[] = [];
    await api.uploadJar(asFile(demoJars().slow), { onProgress: (sent) => told.push(sent) });
    expect(told.at(-1)).toBe(demoJars().slow.length);

    app.engine.mode = 'hang';
    const abort = new AbortController();
    const sending = api.uploadJar(asFile(demoJars().slow), { signal: abort.signal });
    abort.abort();
    await expect(sending).rejects.toMatchObject({ name: 'AbortError' });
  });

  test('an answer that is not what 08-api.md says is a failure, not a broken page', async () => {
    const api = await start();
    app.engine.faults.push({
      match: /POST \/api\/v1\/artifacts/,
      status: 201,
      body: { hash: 1 },
      times: 1,
    });

    await expect(api.uploadJar(asFile(demoJars().slow))).rejects.toBeInstanceOf(ApiFailure);
  });
});

describe('a version', () => {
  test('is read by its hash, with all the pipelines in it', async () => {
    const api = await start();
    const hash = app.engine.backend.seedArtifact('ada', [
      { name: 'x', className: 'a.X' },
      { name: 'y', className: 'a.Y' },
    ]);

    const artifact = await api.artifact(hash);

    expect(artifact.contentHash).toBe(hash);
    expect(artifact.pipelines.map((p) => p.name)).toEqual(['x', 'y']);
  });

  test("that is not there, or is not the caller's, is a 404 not_found", async () => {
    const api = await start();
    const theirs = app.engine.backend.seedArtifact('bob', [{ name: 'x', className: 'a.X' }]);

    await expect(api.artifact(theirs)).rejects.toMatchObject({
      status: 404,
      body: { error: 'not_found' },
    });
    await expect(api.artifact('0'.repeat(64))).rejects.toMatchObject({ status: 404 });
  });
});

describe('the definitions', () => {
  test('are the pipelines with the version, uploader and time, newest version first as the Engine lists them', async () => {
    const api = await start();
    app.engine.backend.seedArtifact(
      'ada',
      [{ name: 'a', className: 'x.A', parameters: [{ name: 'p', required: true }] }],
      { contentHash: 'a'.repeat(64), uploadedAt: '2026-10-05T01:00:00Z' },
    );

    const { definitions, limitations } = await api.definitions();

    expect(typeof limitations).toBe('string');
    expect(definitions).toHaveLength(1);
    expect(definitions[0]).toMatchObject({
      contentHash: 'a'.repeat(64),
      uploadedBy: 'ada',
      uploadedAt: '2026-10-05T01:00:00Z',
      name: 'a',
      className: 'x.A',
      verdict: 'SAFE',
      allowListVersion: '1',
    });
    expect(definitions[0].metadata.parameters).toEqual([
      { name: 'p', required: true, default: null },
    ]);
  });

  test('carry the warnings about the shared resources a pipeline needs', async () => {
    const api = await start();
    app.engine.backend.seedArtifact('ada', [
      { name: 'r', className: 'x.R', resources: ['printer'] },
    ]);

    const { definitions } = await api.definitions();

    expect(definitions[0].warnings).toEqual([
      { kind: 'resource_unknown', resource: 'printer', message: expect.any(String) },
    ]);
  });

  test('only what the Engine says the caller may see: the Console does not filter', async () => {
    const api = await start();
    app.engine.backend.seedArtifact('ada', [{ name: 'mine', className: 'x.M' }]);
    app.engine.backend.seedArtifact('bob', [{ name: 'theirs', className: 'x.T' }]);

    expect((await api.definitions()).definitions.map((d) => d.name)).toEqual(['mine']);
  });

  test('a failure of the Engine is an ApiFailure with its status and body', async () => {
    const api = await start();
    app.engine.faults.push({
      match: /GET \/api\/v1\/definitions/,
      status: 500,
      body: { error: 'internal_error', message: 'boom', errorId: 'e-9' },
      times: 1,
    });

    await expect(api.definitions()).rejects.toMatchObject({
      status: 500,
      body: { error: 'internal_error', errorId: 'e-9' },
    });
  });
});

describe('the runs', () => {
  const slow = (contentHash: string) => ({ contentHash, pipeline: 'demo-slow' });

  test('a run is created with the parameters, and has what the Engine says of it', async () => {
    const api = await start();
    app.engine.backend.autoRun = false;
    const { artifact } = await api.uploadJar(asFile(demoJars().slow));

    const run = await api.createRun({ ...slow(artifact.contentHash), parameters: { steps: '2' } });

    expect(run).toMatchObject({
      state: 'QUEUED',
      pipeline: 'demo-slow',
      source: { kind: 'MANUAL', name: 'ada' },
      parameters: { label: 'demo', steps: '2', delayMillis: '1000' },
      startedAt: null,
      finishedAt: null,
      failure: null,
      unsafeExecution: null,
    });
    expect(run.runId).toMatch(/^[0-9a-f-]{36}$/);
    expect(await api.run(run.runId)).toEqual(run);
  });

  test.each([
    ['an undeclared parameter', { parameters: { nope: '1' } }, 422, 'invalid_parameters'],
    ['a pipeline that is not there', { pipeline: 'nope' }, 404, 'definition_not_found'],
  ] as const)(
    '%s is refused with the status and body of the Engine',
    async (_what, change, status, error) => {
      const api = await start();
      const { artifact } = await api.uploadJar(asFile(demoJars().slow));

      const failure = await api
        .createRun({ ...slow(artifact.contentHash), ...change })
        .catch((e) => e);

      expect(failure).toMatchObject({ status, body: { error } });
    },
  );

  test('an unsafe pipeline is refused, and so is one whose shared resource is not there', async () => {
    const api = await start();
    const unsafe = await api.uploadJar(asFile(demoJars().unsafe));
    const resource = await api.uploadJar(asFile(demoJars().resource));

    expect(
      await api
        .createRun({ contentHash: unsafe.artifact.contentHash, pipeline: 'demo-unsafe' })
        .catch((e) => e),
    ).toMatchObject({ status: 409, body: { error: 'unsafe_not_allowed' } });
    expect(
      await api
        .createRun({ contentHash: resource.artifact.contentHash, pipeline: 'demo-resource' })
        .catch((e) => e),
    ).toMatchObject({
      status: 409,
      body: {
        error: 'resources_unavailable',
        problems: [{ resource: 'demo-printer', problem: 'unknown' }],
      },
    });
  });

  test('the list is the newest first, and can be of one pipeline and limited', async () => {
    const api = await start();
    app.engine.backend.autoRun = false;
    const a = app.engine.backend.seedRun('ada', {
      pipeline: 'one',
      createdAt: '2026-10-05T01:00:00Z',
    });
    const b = app.engine.backend.seedRun('ada', {
      pipeline: 'two',
      createdAt: '2026-10-05T02:00:00Z',
    });

    expect((await api.runs()).map((r) => r.runId)).toEqual([b.runId, a.runId]);
    expect((await api.runs({ pipeline: 'one' })).map((r) => r.runId)).toEqual([a.runId]);
    expect((await api.runs({ limit: 1 })).map((r) => r.runId)).toEqual([b.runId]);
    expect(app.engine.log.at(-1)!.path).toBe('/api/v1/runs?limit=1');
  });

  test('the name of a pipeline in the query is escaped', async () => {
    const api = await start();
    await api.runs({ pipeline: 'a&b=c d' });
    expect(app.engine.log.at(-1)!.path).toBe('/api/v1/runs?pipeline=a%26b%3Dc+d');
  });

  test('a run that is not there is a 404 failure', async () => {
    const api = await start();
    await expect(api.run('00000000-0000-4000-8000-000000000000')).rejects.toMatchObject({
      status: 404,
      body: { error: 'run_not_found' },
    });
  });

  test('a failed run has its failure: type, message (it may be missing) and trace', async () => {
    const api = await start();
    const failed = app.engine.backend.seedRun('ada', {
      state: 'FAILED',
      failure: { type: 'java.lang.Boom', message: null, trace: 'java.lang.Boom\n\tat x' },
    });

    expect((await api.run(failed.runId)).failure).toEqual({
      type: 'java.lang.Boom',
      message: null,
      trace: 'java.lang.Boom\n\tat x',
    });
  });

  test('a run that was started by an unsafe setting says who set it and when', async () => {
    const api = await start();
    const made = app.engine.backend.seedRun('ada', {
      unsafeExecution: { setBy: 'root', setAt: '2026-10-05T00:00:00Z' },
    });
    expect((await api.run(made.runId)).unsafeExecution).toEqual({
      setBy: 'root',
      setAt: '2026-10-05T00:00:00Z',
    });
  });

  test('cancelling says whether the run was cancelled at once (200) or asked to stop (202)', async () => {
    const api = await start();
    app.engine.backend.autoRun = false;
    const queued = app.engine.backend.seedRun('ada', { state: 'QUEUED' });
    const running = app.engine.backend.seedRun('ada', {
      state: 'RUNNING',
      startedAt: '2026-10-05T01:00:00Z',
    });
    const done = app.engine.backend.seedRun('ada', { state: 'SUCCEEDED' });

    expect(await api.cancelRun(queued.runId)).toEqual({
      runId: queued.runId,
      state: 'CANCELLED',
      cancellation: 'cancelled',
    });
    expect(await api.cancelRun(running.runId)).toMatchObject({ cancellation: 'requested' });
    await expect(api.cancelRun(done.runId)).rejects.toMatchObject({
      status: 409,
      body: { error: 'already_finished' },
    });
  });

  test('the log is read from a cursor: the entries and the cursor to go on from', async () => {
    const api = await start();
    const run = app.engine.backend.seedRun('ada', { state: 'RUNNING' });
    app.engine.backend.write(run.runId, 'one', { stream: 'STDERR', line: 'two' }, 'three');

    const all = await api.log(run.runId, 0);
    expect(all.last).toBe(3);
    expect(all.entries.map((e) => [e.seq, e.stream, e.line])).toEqual([
      [1, 'STDOUT', 'one'],
      [2, 'STDERR', 'two'],
      [3, 'STDOUT', 'three'],
    ]);
    expect(Number.isNaN(Date.parse(all.entries[0].at))).toBe(false);

    expect(await api.log(run.runId, 2)).toMatchObject({ entries: [{ seq: 3 }], last: 3 });
    expect(await api.log(run.runId, 3)).toEqual({ entries: [], last: 3 });
    expect((await api.log(run.runId, 0, 2)).entries.map((e) => e.seq)).toEqual([1, 2]);
    expect(app.engine.log.at(-1)!.path).toBe(`/api/v1/runs/${run.runId}/log?after=0&limit=2`);
  });

  test('a log that is not there is a 404 failure', async () => {
    const api = await start();
    await expect(api.log('00000000-0000-4000-8000-000000000000', 0)).rejects.toMatchObject({
      status: 404,
    });
  });

  test('an answer that is not what 08-api.md says is a failure, never a half-read run', async () => {
    const api = await start();
    app.engine.faults.push({
      match: /GET \/api\/v1\/runs$/,
      status: 200,
      body: { runs: [{ runId: 7 }] },
      times: 1,
    });
    await expect(api.runs()).rejects.toBeInstanceOf(ApiFailure);

    app.engine.faults.push({
      match: /GET \/api\/v1\/runs$/,
      status: 200,
      body: '{"runs": [',
      times: 1,
    });
    await expect(api.runs()).rejects.toBeInstanceOf(ApiFailure);
  });

  test('when the Engine cannot be reached it is a failure with status 0', async () => {
    const api = await start();
    app.engine.faults.push({ match: /GET \/api\/v1\/runs$/, status: 0, drop: true, times: 1 });
    await expect(api.runs()).rejects.toMatchObject({ status: 0 });
  });

  test('a state the Console does not know yet is passed on as it came', async () => {
    const api = await start();
    const odd = app.engine.backend.seedRun('ada');
    // @ts-expect-error a state of a newer Engine
    app.engine.backend.setState(odd.runId, 'PAUSED');
    expect((await api.run(odd.runId)).state).toBe('PAUSED');
  });
});

test('the jar of a fake has the pipelines that the Fake is told', async () => {
  const api = await start();
  const jar = fakeJar([
    { name: 'x', className: 'a.X' },
    { name: 'y', className: 'a.Y' },
  ]);
  const { artifact } = await api.uploadJar(asFile(jar));
  expect(artifact.pipelines.map((p) => p.name)).toEqual(['x', 'y']);
});
