// What the API of the pipelines promises (08-api.md: upload, definitions, runs, cancel, log), as
// tests that run on any server that claims to be the Engine: the Fake of test-support (always, in
// `npm test`) and a real packaged Engine (`npm run test:contract`). Only what both must do is
// asserted: a real Engine runs the sample pipelines for real (dev/sample-pipelines), the Fake runs
// what it knows of them, and both must say the same things about them. A Fake that fails here does
// not stand for the Engine.

import { createHash } from 'node:crypto';
import { describe, expect, test, vi } from 'vitest';
import type { DemoJars } from '../test-support/fake-jars';
import type { ContractCaller } from './system-contract';

export interface PipelinesContractSetup {
  baseUrl: () => string;
  /** At least two developers and an admin. */
  callers: () => ContractCaller[];
  jars: () => DemoJars;
  /** What the server refuses to take as an upload (UPLOAD_MAX_BYTES). */
  uploadLimitBytes: number;
}

interface Answer {
  status: number;
  headers: Headers;
  body: any;
}

const TERMINAL = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'TIMED_OUT'];
const STATES = [
  'QUEUED',
  'WAITING_FOR_RESOURCES',
  'INITIALIZING',
  'RUNNING',
  'TIMED_OUT_UNFINISHED',
  ...TERMINAL,
];
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export function describePipelinesContract(name: string, setup: PipelinesContractSetup) {
  const developers = () => setup.callers().filter((c) => c.role === 'developer');
  const ada = () => developers()[0];
  const bob = () => developers()[1];
  const root = () => setup.callers().find((c) => c.role === 'admin')!;

  async function call(
    who: ContractCaller | null,
    method: string,
    path: string,
    body?: BodyInit,
    contentType?: string,
  ): Promise<Answer> {
    const headers: Record<string, string> = {};
    if (who) headers.Authorization = `Bearer ${who.token}`;
    if (contentType) headers['Content-Type'] = contentType;
    const response = await fetch(`${setup.baseUrl()}${path}`, { method, headers, body });
    const text = await response.text();
    let parsed: unknown = null;
    try {
      parsed = text === '' ? null : JSON.parse(text);
    } catch {
      parsed = text;
    }
    return { status: response.status, headers: response.headers, body: parsed };
  }

  const upload = (who: ContractCaller | null, bytes: Uint8Array) =>
    call(
      who,
      'POST',
      '/api/v1/artifacts',
      bytes as unknown as BodyInit,
      'application/octet-stream',
    );
  const createRun = (who: ContractCaller | null, payload: unknown) =>
    call(who, 'POST', '/api/v1/runs', JSON.stringify(payload), 'application/json');
  const getRun = (who: ContractCaller, runId: string) => call(who, 'GET', `/api/v1/runs/${runId}`);

  const sha256 = (bytes: Uint8Array) => createHash('sha256').update(bytes).digest('hex');

  /** The version of a sample pipeline, uploaded by [who] (200 or 201: it may be there already). */
  async function uploaded(who: ContractCaller, bytes: Uint8Array) {
    const answer = await upload(who, bytes);
    expect([200, 201]).toContain(answer.status);
    return answer.body as { contentHash: string; pipelines: Array<Record<string, any>> };
  }

  const untilTerminal = (who: ContractCaller, runId: string) =>
    vi.waitFor(
      async () => {
        const { body } = await getRun(who, runId);
        expect(TERMINAL).toContain(body.state);
        return body;
      },
      { timeout: 30_000, interval: 100 },
    );

  describe(`uploading and reading definitions with ${name}`, () => {
    test('an upload without a token is a 401', async () => {
      expect((await upload(null, setup.jars().slow)).status).toBe(401);
    });

    test('a new jar is a 201 and the same bytes again are a 200 with the same version', async () => {
      const bytes = setup.jars().slow;
      const first = await upload(ada(), bytes);
      expect([200, 201]).toContain(first.status);
      const again = await upload(ada(), bytes);
      expect(again.status).toBe(200);
      expect(again.body.contentHash).toBe(first.body.contentHash);
      expect(again.body.uploadedAt).toBe(first.body.uploadedAt);
    });

    test('the version says what it is: the hash of the bytes, their size, who uploaded and when', async () => {
      const bytes = setup.jars().slow;
      const { body } = await upload(ada(), bytes);
      expect(body.contentHash).toBe(sha256(bytes));
      expect(body.sizeBytes).toBe(bytes.length);
      expect(body.uploadedBy).toBe(ada().name);
      expect(Number.isNaN(Date.parse(body.uploadedAt))).toBe(false);
      expect(typeof body.limitations).toBe('string');
    });

    test('a pipeline is described by its name, class, declared parameters, access and verdict', async () => {
      const { pipelines } = await uploaded(ada(), setup.jars().slow);
      expect(pipelines).toHaveLength(1);
      const [pipeline] = pipelines;
      expect(pipeline.name).toBe('demo-slow');
      expect(pipeline.className).toBe('samples.slow.SlowPipeline');
      expect(pipeline.metadata.parameters).toEqual([
        { name: 'label', required: false, default: 'demo' },
        { name: 'steps', required: false, default: '30' },
        { name: 'delayMillis', required: false, default: '1000' },
      ]);
      expect(pipeline.metadata.files).toEqual([]);
      expect(pipeline.metadata.network.unrestricted).toBe(false);
      expect(pipeline.metadata.processes.unrestricted).toBe(false);
      expect(pipeline.metadata.resources).toEqual([]);
      expect(pipeline.verdict).toBe('SAFE');
      expect(pipeline.reasons).toEqual([]);
      expect(typeof pipeline.allowListVersion).toBe('string');
      expect(pipeline.allowUnsafeExecution).toBe(false);
      expect(pipeline.warnings).toEqual([]);
    });

    test('an unsafe pipeline is UNSAFE, with its reasons and the path to each', async () => {
      const { pipelines } = await uploaded(ada(), setup.jars().unsafe);
      const [pipeline] = pipelines;
      expect(pipeline.verdict).toBe('UNSAFE');
      expect(pipeline.allowUnsafeExecution).toBe(false);
      expect(pipeline.reasons.length).toBeGreaterThan(0);
      for (const reason of pipeline.reasons) {
        expect(typeof reason.kind).toBe('string');
        expect(Array.isArray(reason.path)).toBe(true);
      }
      const unlisted = pipeline.reasons.find((r: any) => r.kind === 'NOT_ALLOW_LISTED');
      expect(unlisted.className).toBe('java.io.File');
      expect(unlisted.path).toEqual(['samples.unsafe.UnsafePipeline', 'java.io.File']);
    });

    test('a pipeline that declares a shared resource lists it', async () => {
      const { pipelines } = await uploaded(ada(), setup.jars().resource);
      expect(pipelines[0].metadata.resources).toEqual(['demo-printer']);
    });

    test('bytes that are no jar are a 422 not_a_jar, with a message', async () => {
      const { status, body } = await upload(ada(), setup.jars().junk);
      expect(status).toBe(422);
      expect(body.error).toBe('not_a_jar');
      expect(typeof body.message).toBe('string');
    });

    test('a jar with no pipeline in it is a 422 no_pipeline_found', async () => {
      const { status, body } = await upload(ada(), setup.jars().noPipeline);
      expect(status).toBe(422);
      expect(body.error).toBe('no_pipeline_found');
    });

    test('a file over the upload limit is a 413 too_large', async () => {
      const { status, body } = await upload(ada(), new Uint8Array(setup.uploadLimitBytes + 1));
      expect(status).toBe(413);
      expect(body.error).toBe('too_large');
    });

    test('the definitions list has each pipeline with the version it is in and who uploaded it', async () => {
      const slow = await uploaded(ada(), setup.jars().slow);
      const { status, body } = await call(ada(), 'GET', '/api/v1/definitions');
      expect(status).toBe(200);
      expect(typeof body.limitations).toBe('string');
      const entry = body.definitions.find(
        (d: any) => d.contentHash === slow.contentHash && d.name === 'demo-slow',
      );
      expect(entry).toMatchObject({
        uploadedBy: ada().name,
        className: 'samples.slow.SlowPipeline',
        verdict: 'SAFE',
        allowUnsafeExecution: false,
      });
      expect(Number.isNaN(Date.parse(entry.uploadedAt))).toBe(false);
      expect(entry.metadata.parameters).toHaveLength(3);
      expect(Array.isArray(entry.reasons)).toBe(true);
      expect(Array.isArray(entry.warnings)).toBe(true);
      expect(typeof entry.allowListVersion).toBe('string');
    });

    test('a developer sees what they uploaded, not what another developer did; an admin sees both', async () => {
      const mine = await uploaded(ada(), setup.jars().failing);
      const seenByBob = (await call(bob(), 'GET', '/api/v1/definitions')).body.definitions;
      expect(seenByBob.some((d: any) => d.contentHash === mine.contentHash)).toBe(false);
      const seenByAda = (await call(ada(), 'GET', '/api/v1/definitions')).body.definitions;
      expect(seenByAda.some((d: any) => d.contentHash === mine.contentHash)).toBe(true);
      const seenByRoot = (await call(root(), 'GET', '/api/v1/definitions')).body.definitions;
      expect(seenByRoot.some((d: any) => d.contentHash === mine.contentHash)).toBe(true);
    });

    test('the version of another developer is a 404 not_found, as if it did not exist', async () => {
      const mine = await uploaded(ada(), setup.jars().failing);
      const theirs = await call(bob(), 'GET', `/api/v1/artifacts/${mine.contentHash}`);
      const nothing = await call(bob(), 'GET', `/api/v1/artifacts/${'0'.repeat(64)}`);
      expect(theirs.status).toBe(404);
      expect(theirs.body.error).toBe('not_found');
      expect(nothing.status).toBe(404);
      expect(nothing.body.error).toBe(theirs.body.error);
      expect((await call(ada(), 'GET', `/api/v1/artifacts/${mine.contentHash}`)).status).toBe(200);
    });
  });

  describe(`runs with ${name}`, () => {
    test('a run is made queued, with its version, pipeline, source, parameters (defaults applied) and a Location', async () => {
      const { contentHash } = await uploaded(ada(), setup.jars().slow);
      const answer = await createRun(ada(), {
        contentHash,
        pipeline: 'demo-slow',
        parameters: { steps: '2', delayMillis: '50' },
      });
      expect(answer.status).toBe(201);
      const run = answer.body;
      expect(run.runId).toMatch(UUID);
      expect(answer.headers.get('location')).toBe(`/api/v1/runs/${run.runId}`);
      expect(STATES).toContain(run.state);
      expect(run).toMatchObject({
        contentHash,
        pipeline: 'demo-slow',
        className: 'samples.slow.SlowPipeline',
        source: { kind: 'MANUAL', name: ada().name },
        parameters: { label: 'demo', steps: '2', delayMillis: '50' },
      });
      expect(Number.isNaN(Date.parse(run.createdAt))).toBe(false);
      await untilTerminal(ada(), run.runId);
    });

    test('a run that finishes has started and finished, and its log has every line in order from 1', async () => {
      const { contentHash } = await uploaded(ada(), setup.jars().slow);
      const created = await createRun(ada(), {
        contentHash,
        pipeline: 'demo-slow',
        parameters: { label: 'c', steps: '3', delayMillis: '30' },
      });
      const run = await untilTerminal(ada(), created.body.runId);
      expect(run.state).toBe('SUCCEEDED');
      expect(Number.isNaN(Date.parse(run.startedAt))).toBe(false);
      expect(Number.isNaN(Date.parse(run.finishedAt))).toBe(false);
      expect(run.failure).toBeNull();

      const log = (await call(ada(), 'GET', `/api/v1/runs/${run.runId}/log`)).body;
      const lines = log.entries.map((e: any) => e.line);
      expect(lines).toEqual([
        '[c] starting 3 steps, 30ms each',
        '[c] step 1/3 done',
        '[c] step 2/3 done',
        '[c] step 3/3 done',
        '[c] finished',
      ]);
      expect(log.entries.map((e: any) => e.seq)).toEqual([1, 2, 3, 4, 5]);
      expect(log.entries.every((e: any) => e.stream === 'STDOUT')).toBe(true);
      expect(log.entries.every((e: any) => !Number.isNaN(Date.parse(e.at)))).toBe(true);
      expect(log.last).toBe(5);
    });

    test('the log is read on from a cursor: after, and a limit; nothing new leaves the cursor where it was', async () => {
      const { contentHash } = await uploaded(ada(), setup.jars().slow);
      const created = await createRun(ada(), {
        contentHash,
        pipeline: 'demo-slow',
        parameters: { steps: '3', delayMillis: '20' },
      });
      await untilTerminal(ada(), created.body.runId);
      const path = `/api/v1/runs/${created.body.runId}/log`;

      const one = (await call(ada(), 'GET', `${path}?after=2&limit=1`)).body;
      expect(one.entries.map((e: any) => e.seq)).toEqual([3]);
      expect(one.last).toBe(3);

      const rest = (await call(ada(), 'GET', `${path}?after=3`)).body;
      expect(rest.entries.map((e: any) => e.seq)).toEqual([4, 5]);

      const none = (await call(ada(), 'GET', `${path}?after=5`)).body;
      expect(none).toEqual({ entries: [], last: 5 });
    });

    test('a failed run says how: type, message and trace; and its log has what it wrote', async () => {
      const { contentHash } = await uploaded(ada(), setup.jars().failing);
      const created = await createRun(ada(), {
        contentHash,
        pipeline: 'demo-failing',
        parameters: { reason: 'on purpose <b>1</b>' },
      });
      const run = await untilTerminal(ada(), created.body.runId);
      expect(run.state).toBe('FAILED');
      expect(run.failure.type).toContain('IllegalStateException');
      expect(run.failure.message).toBe('on purpose <b>1</b>');
      expect(run.failure.trace).toContain('on purpose <b>1</b>');
      const log = (await call(ada(), 'GET', `/api/v1/runs/${run.runId}/log`)).body;
      expect(log.entries.map((e: any) => e.line)).toContain('about to fail');
    });

    test('a run is cancelled with a 200 when it had not started or a 202 when it has been asked to stop, and then it ends CANCELLED; a second cancel is a 409', async () => {
      const { contentHash } = await uploaded(ada(), setup.jars().slow);
      const created = await createRun(ada(), {
        contentHash,
        pipeline: 'demo-slow',
        parameters: { steps: '60', delayMillis: '1000' },
      });
      const cancel = await call(ada(), 'POST', `/api/v1/runs/${created.body.runId}/cancel`);
      expect([200, 202]).toContain(cancel.status);
      expect(cancel.body.runId).toBe(created.body.runId);
      expect(cancel.body.cancellation).toBe(cancel.status === 200 ? 'cancelled' : 'requested');

      const run = await untilTerminal(ada(), created.body.runId);
      expect(run.state).toBe('CANCELLED');

      const again = await call(ada(), 'POST', `/api/v1/runs/${created.body.runId}/cancel`);
      expect(again.status).toBe(409);
      expect(again.body.error).toBe('already_finished');
    });

    test('an unsafe pipeline is not run until an admin allows it: 409 unsafe_not_allowed, and no run is left', async () => {
      const { contentHash } = await uploaded(ada(), setup.jars().unsafe);
      const before = (await call(ada(), 'GET', '/api/v1/runs?pipeline=demo-unsafe')).body.runs;
      const { status, body } = await createRun(ada(), { contentHash, pipeline: 'demo-unsafe' });
      expect(status).toBe(409);
      expect(body.error).toBe('unsafe_not_allowed');
      const after = (await call(ada(), 'GET', '/api/v1/runs?pipeline=demo-unsafe')).body.runs;
      expect(after).toHaveLength(before.length);
    });

    test('parameters the pipeline does not declare are a 422 invalid_parameters, each named', async () => {
      const { contentHash } = await uploaded(ada(), setup.jars().slow);
      const { status, body } = await createRun(ada(), {
        contentHash,
        pipeline: 'demo-slow',
        parameters: { nope: '1' },
      });
      expect(status).toBe(422);
      expect(body.error).toBe('invalid_parameters');
      expect(body.problems).toEqual([{ name: 'nope', problem: 'undeclared' }]);
    });

    test('a shared resource that is not defined is a 409 resources_unavailable, each named', async () => {
      const { contentHash } = await uploaded(ada(), setup.jars().resource);
      // The Engine cannot delete a shared resource: on an Engine where an earlier run of the
      // contract tests (the admin's) defined it, it is disabled here, so the problem is the other one.
      const defined = await call(root(), 'GET', '/api/v1/resources/demo-printer');
      if (defined.status === 200) {
        await call(root(), 'PATCH', '/api/v1/resources/demo-printer', JSON.stringify({ enabled: false }), 'application/json');
      }
      const { status, body } = await createRun(ada(), { contentHash, pipeline: 'demo-resource' });
      expect(status).toBe(409);
      expect(body.error).toBe('resources_unavailable');
      expect(body.problems).toEqual([
        { resource: 'demo-printer', problem: defined.status === 200 ? 'disabled' : 'unknown' },
      ]);
    });

    test('a version or pipeline that is not there, or is not yours, is a 404 definition_not_found', async () => {
      const { contentHash } = await uploaded(ada(), setup.jars().slow);
      const nothing = await createRun(ada(), {
        contentHash: '0'.repeat(64),
        pipeline: 'demo-slow',
      });
      const wrongName = await createRun(ada(), { contentHash, pipeline: 'nope' });
      const notMine = await createRun(bob(), { contentHash, pipeline: 'demo-slow' });
      for (const answer of [nothing, wrongName, notMine]) {
        expect(answer.status).toBe(404);
        expect(answer.body.error).toBe('definition_not_found');
      }
    });

    test('a body that is not what a run needs is a 400 bad_request', async () => {
      const { status, body } = await call(
        ada(),
        'POST',
        '/api/v1/runs',
        'garbage',
        'application/json',
      );
      expect(status).toBe(400);
      expect(body.error).toBe('bad_request');
    });

    test('a run of another developer, one that is not there and one that is no id are all the same 404 run_not_found', async () => {
      const { contentHash } = await uploaded(ada(), setup.jars().slow);
      const created = await createRun(ada(), {
        contentHash,
        pipeline: 'demo-slow',
        parameters: { steps: '1', delayMillis: '10' },
      });
      await untilTerminal(ada(), created.body.runId);
      const id = created.body.runId;
      const missing = '00000000-0000-4000-8000-000000000000';
      for (const path of [
        `/api/v1/runs/${id}`,
        `/api/v1/runs/${id}/log`,
        `/api/v1/runs/${missing}`,
        `/api/v1/runs/${missing}/log`,
        '/api/v1/runs/not-an-id',
      ]) {
        const as = path.includes(id) ? bob() : ada();
        const answer = await call(as, 'GET', path);
        expect([path, answer.status, answer.body.error]).toEqual([path, 404, 'run_not_found']);
      }
      const cancel = await call(bob(), 'POST', `/api/v1/runs/${id}/cancel`);
      expect([cancel.status, cancel.body.error]).toEqual([404, 'run_not_found']);
    });

    test('the runs list has the newest first, only the runs of the caller, and can be limited and filtered by pipeline', async () => {
      const slow = await uploaded(ada(), setup.jars().slow);
      const failing = await uploaded(ada(), setup.jars().failing);
      const a = await createRun(ada(), {
        contentHash: slow.contentHash,
        pipeline: 'demo-slow',
        parameters: { steps: '1', delayMillis: '10' },
      });
      const b = await createRun(ada(), {
        contentHash: failing.contentHash,
        pipeline: 'demo-failing',
      });

      const list = await call(ada(), 'GET', '/api/v1/runs');
      expect(list.status).toBe(200);
      const ids = list.body.runs.map((r: any) => r.runId);
      expect(ids.indexOf(b.body.runId)).toBeLessThan(ids.indexOf(a.body.runId));
      expect(ids.indexOf(b.body.runId)).toBeGreaterThanOrEqual(0);

      const only = (await call(ada(), 'GET', '/api/v1/runs?pipeline=demo-failing')).body.runs;
      expect(only.length).toBeGreaterThan(0);
      expect(only.every((r: any) => r.pipeline === 'demo-failing')).toBe(true);

      const one = (await call(ada(), 'GET', '/api/v1/runs?limit=1')).body.runs;
      expect(one.map((r: any) => r.runId)).toEqual([b.body.runId]);

      const theirs = (await call(bob(), 'GET', '/api/v1/runs')).body.runs;
      expect(theirs.some((r: any) => [a.body.runId, b.body.runId].includes(r.runId))).toBe(false);
      const all = (await call(root(), 'GET', '/api/v1/runs?limit=200')).body.runs;
      expect(all.some((r: any) => r.runId === b.body.runId)).toBe(true);

      await untilTerminal(ada(), a.body.runId);
      await untilTerminal(ada(), b.body.runId);
    });

    test('every call without a token is a 401', async () => {
      for (const [method, path] of [
        ['GET', '/api/v1/definitions'],
        ['GET', '/api/v1/runs'],
        ['POST', '/api/v1/runs'],
        ['GET', `/api/v1/runs/${'0'.repeat(8)}-0000-4000-8000-000000000000`],
        ['POST', `/api/v1/runs/${'0'.repeat(8)}-0000-4000-8000-000000000000/cancel`],
        ['GET', `/api/v1/runs/${'0'.repeat(8)}-0000-4000-8000-000000000000/log`],
      ]) {
        expect([path, (await call(null, method, path)).status]).toEqual([path, 401]);
      }
    });
  });
}
