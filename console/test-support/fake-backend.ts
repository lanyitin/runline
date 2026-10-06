// What the Fake Engine does for the API of the pipelines (08-api.md: upload, definitions, runs,
// cancel, log). It keeps what an Engine keeps (the versions uploaded, the runs and their logs) in
// memory, applies the rules that the Console depends on (who may see what, the order of the checks
// of a new run, 200 and 201, the cursor of the log) and runs the sample pipelines of
// dev/sample-pipelines the way they behave. It does not analyse a jar: see fake-jars.ts.
//
// It knows nothing of HTTP: fake-engine.ts reads the request and writes the answer. The contract
// tests (contract/pipelines-contract.ts) hold it to the real Engine.

import { createHash, randomUUID } from 'node:crypto';
import {
  FAKE_PIPELINES_ENTRY,
  type FakeParameter,
  type FakePipeline,
  type FakeReason,
} from './fake-jars';
import { readZip } from './zip';
import {
  answer,
  failure,
  forbidden,
  type ApiAnswer,
  type ApiRequest,
  type FakeCallerRef,
  type FakeRoute,
} from './fake-api';
import { FakeAllowList, judge } from './fake-allowlist';
import { FakeResources } from './fake-resources';
import { FakeTriggers, type FakeTrigger } from './fake-triggers';

export type { ApiAnswer, ApiRequest, FakeCallerRef } from './fake-api';

export type RunState =
  | 'QUEUED'
  | 'WAITING_FOR_RESOURCES'
  | 'INITIALIZING'
  | 'RUNNING'
  | 'TIMED_OUT_UNFINISHED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'CANCELLED'
  | 'INTERRUPTED'
  | 'TIMED_OUT';

const TERMINAL: readonly RunState[] = [
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'INTERRUPTED',
  'TIMED_OUT',
];

export interface FakeLogEntry {
  seq: number;
  at: string;
  stream: 'STDOUT' | 'STDERR';
  line: string;
}

export interface FakeFailure {
  type: string;
  message: string | null;
  trace: string;
}

export interface FakeRun {
  runId: string;
  /** The name of the caller who made the run: the developer who may see it. */
  owner: string;
  state: RunState;
  contentHash: string;
  pipeline: string;
  className: string;
  source: { kind: 'MANUAL' | 'TRIGGER'; name: string };
  parameters: Record<string, string>;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  failure: FakeFailure | null;
  unsafeExecution: { setBy: string; setAt: string } | null;
  log: FakeLogEntry[];
}

export interface Definition {
  className: string;
  name: string;
  metadata: {
    parameters: FakeParameter[];
    files: Array<{ scope: string; mode: string }>;
    network: { unrestricted: boolean; allow: string[] };
    processes: { unrestricted: boolean; allow: string[] };
    resources: string[];
  };
  verdict: 'SAFE' | 'UNSAFE';
  reasons: Array<Required<FakeReason>>;
  /** The reasons that do not depend on the allow-list: the access that is not limited. */
  fixedReasons: Array<Required<FakeReason>>;
  /** The classes the pipeline refers to: one that no entry covers is a reason of its own. */
  references: Array<{ className: string; path: string[] }>;
  allowListVersion: string;
  allowUnsafeExecution: boolean;
  unsafeSetBy: string | null;
  unsafeSetAt: string | null;
}

interface Artifact {
  contentHash: string;
  sizeBytes: number;
  uploadedBy: string;
  uploadedAt: string;
  definitions: Definition[];
}

const LIMITATIONS =
  'The analysis only checks the class references of the compiled classes (a Fake).';

const RESOURCE_NAME = /^[A-Za-z0-9._-]+$/;
const PIPELINE_NAME = /^(?!\.{1,2}$)[A-Za-z0-9._-]+$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

export interface FakeBackendOptions {
  /** A body larger than this is refused with a 413, as UPLOAD_MAX_BYTES does. */
  maxUploadBytes?: number;
  /** Whether a run that is made goes on by itself (starts, writes its log, ends). */
  autoRun?: boolean;
  /** How long a made run stays queued before it starts. */
  startDelayMs?: number;
  /** How long `demo-resource` holds its shared resource (the sample holds it for 20 seconds). */
  resourceHoldMs?: number;
}

export class FakeBackend {
  readonly artifacts = new Map<string, Artifact>();
  readonly runs: FakeRun[] = [];
  /** The shared resources that are defined, who holds them and who waits. */
  readonly resources = new FakeResources();
  maxUploadBytes: number;
  autoRun: boolean;
  startDelayMs: number;
  resourceHoldMs: number;
  private readonly timers = new Set<ReturnType<typeof setTimeout>>();
  /** The triggers an admin has bound, and their firings. */
  readonly triggers = new FakeTriggers(this);
  /** The entries that decide what is SAFE, and the versions they had. */
  readonly allowList = new FakeAllowList(this);
  /** The routes of the parts the backend is made of, apart from its own. */
  private readonly routes: FakeRoute[] = [
    ...this.triggers.routes,
    ...this.allowList.routes,
    ...this.resources.routes,
    {
      method: 'DELETE',
      pattern: /^\/api\/v1\/artifacts\/([^/]+)$/,
      admin: true,
      handle: (_request, match) => this.deleteArtifact(decodeURIComponent(match[1])),
    },
    {
      method: 'PUT',
      pattern: /^\/api\/v1\/definitions\/([^/]+)\/([^/]+)\/unsafe-execution$/,
      admin: true,
      handle: (request, match) =>
        this.setUnsafeExecution(request, decodeURIComponent(match[1]), decodeURIComponent(match[2])),
    },
  ];
  /** An answer, once, to the next upload instead of the real one (a refusal the Fake does not make). */
  nextUploadRefusal: ApiAnswer | null = null;

  constructor(options: FakeBackendOptions = {}) {
    this.maxUploadBytes = options.maxUploadBytes ?? 50 * 1024 * 1024;
    this.autoRun = options.autoRun ?? true;
    this.startDelayMs = options.startDelayMs ?? 5;
    this.resourceHoldMs = options.resourceHoldMs ?? 20_000;
  }

  stop() {
    for (const timer of this.timers) clearTimeout(timer);
    this.timers.clear();
  }

  // ---- routing -------------------------------------------------------------------------------

  /** Whether [path] is one of this API for [method]: an answer of 401 and not of 404 without a token. */
  handles(method: string, path: string): boolean {
    if (this.routes.some((r) => r.method === method && r.pattern.test(path))) return true;
    return [
      ['POST', /^\/api\/v1\/artifacts$/],
      ['GET', /^\/api\/v1\/artifacts\/[^/]+$/],
      ['GET', /^\/api\/v1\/definitions$/],
      ['POST', /^\/api\/v1\/runs$/],
      ['GET', /^\/api\/v1\/runs$/],
      ['GET', /^\/api\/v1\/runs\/[^/]+$/],
      ['POST', /^\/api\/v1\/runs\/[^/]+\/cancel$/],
      ['GET', /^\/api\/v1\/runs\/[^/]+\/log$/],
    ].some(([m, pattern]) => m === method && (pattern as RegExp).test(path));
  }

  /** Answers [request], or null when the path is not one of this API. */
  handle(request: ApiRequest): ApiAnswer | null {
    const { method, path } = request;
    for (const route of this.routes) {
      const found = route.method === method ? path.match(route.pattern) : null;
      if (!found) continue;
      if (route.admin && request.caller.role !== 'admin') return forbidden();
      return route.handle(request, found);
    }
    let match: RegExpMatchArray | null;
    if (path === '/api/v1/artifacts' && method === 'POST') return this.upload(request);
    if ((match = path.match(/^\/api\/v1\/artifacts\/([^/]+)$/)) && method === 'GET') {
      return this.readArtifact(request, match[1]);
    }
    if (path === '/api/v1/definitions' && method === 'GET') return this.definitions(request);
    if (path === '/api/v1/runs' && method === 'POST') return this.createRun(request);
    if (path === '/api/v1/runs' && method === 'GET') return this.listRuns(request);
    if ((match = path.match(/^\/api\/v1\/runs\/([^/]+)$/)) && method === 'GET') {
      return this.readRun(request, match[1]);
    }
    if ((match = path.match(/^\/api\/v1\/runs\/([^/]+)\/cancel$/)) && method === 'POST') {
      return this.cancel(request, match[1]);
    }
    if ((match = path.match(/^\/api\/v1\/runs\/([^/]+)\/log$/)) && method === 'GET') {
      return this.readLog(request, match[1]);
    }
    return null;
  }

  private sees(caller: FakeCallerRef, owner: string) {
    return caller.role === 'admin' || caller.name === owner;
  }

  // ---- upload and definitions ----------------------------------------------------------------

  private upload({ caller, body }: ApiRequest): ApiAnswer {
    if (this.nextUploadRefusal) {
      const refusal = this.nextUploadRefusal;
      this.nextUploadRefusal = null;
      return refusal;
    }
    if (body.length > this.maxUploadBytes) {
      return failure(413, 'too_large', 'The file is larger than the upload limit.');
    }
    const entries = readZip(body);
    if (!entries) return failure(422, 'not_a_jar', 'The file is not a valid jar.');

    const contentHash = createHash('sha256').update(body).digest('hex');
    const known = this.artifacts.get(contentHash);
    if (known) return answer(200, this.artifactDoc(known));

    const bundled = [...entries.keys()].find((name) => name.startsWith('dev/lawlan/runline/core/'));
    if (bundled) {
      return failure(422, 'core_classes_bundled', `The jar contains ${bundled}.`);
    }
    const listed = entries.get(FAKE_PIPELINES_ENTRY);
    const pipelines: FakePipeline[] = listed
      ? JSON.parse(Buffer.from(listed).toString('utf8'))
      : [];
    if (pipelines.length === 0) {
      return failure(422, 'no_pipeline_found', 'The jar declares no pipeline.');
    }
    const names = pipelines.map((p) => p.name);
    const duplicate = names.find((n, i) => names.indexOf(n) !== i);
    if (duplicate) {
      return failure(422, 'duplicate_pipeline_name', `Two pipelines are named ${duplicate}.`);
    }
    const invalid = names.filter((n) => !PIPELINE_NAME.test(n));
    if (invalid.length > 0) {
      return failure(422, 'invalid_pipeline_name', `Not allowed: ${invalid.join(', ')}.`);
    }

    const artifact: Artifact = {
      contentHash,
      sizeBytes: body.length,
      uploadedBy: caller.name,
      uploadedAt: new Date().toISOString(),
      definitions: pipelines.map((p) => this.describe(p)),
    };
    this.artifacts.set(contentHash, artifact);
    return answer(201, this.artifactDoc(artifact));
  }

  private pipelineDoc(d: Definition) {
    return {
      className: d.className,
      name: d.name,
      metadata: d.metadata,
      verdict: d.verdict,
      reasons: d.reasons,
      allowListVersion: d.allowListVersion,
      allowUnsafeExecution: d.allowUnsafeExecution,
      warnings: d.metadata.resources.flatMap((resource) => {
        const enabled = this.resources.enabledOf(resource);
        if (enabled === true) return [];
        return [
          enabled === undefined
            ? {
                kind: 'resource_unknown',
                resource,
                message: `The resource ${resource} is not defined.`,
              }
            : {
                kind: 'resource_disabled',
                resource,
                message: `The resource ${resource} is disabled.`,
              },
        ];
      }),
    };
  }

  private artifactDoc(a: Artifact) {
    return {
      contentHash: a.contentHash,
      sizeBytes: a.sizeBytes,
      uploadedBy: a.uploadedBy,
      uploadedAt: a.uploadedAt,
      pipelines: a.definitions.map((d) => this.pipelineDoc(d)),
      limitations: LIMITATIONS,
    };
  }

  private readArtifact({ caller }: ApiRequest, contentHash: string): ApiAnswer {
    const artifact = this.artifacts.get(contentHash);
    if (!artifact || !this.sees(caller, artifact.uploadedBy)) {
      return failure(404, 'not_found', 'No such version.');
    }
    return answer(200, this.artifactDoc(artifact));
  }

  private definitions({ caller }: ApiRequest): ApiAnswer {
    const definitions = [...this.artifacts.values()]
      .filter((a) => this.sees(caller, a.uploadedBy))
      .flatMap((a) =>
        a.definitions.map((d) => ({
          contentHash: a.contentHash,
          uploadedBy: a.uploadedBy,
          uploadedAt: a.uploadedAt,
          ...this.pipelineDoc(d),
        })),
      );
    return answer(200, { definitions, limitations: LIMITATIONS });
  }

  /** `DELETE /api/v1/artifacts/{contentHash}`: a version that no trigger and no run refers to. */
  private deleteArtifact(contentHash: string): ApiAnswer {
    if (!this.artifacts.has(contentHash)) return failure(404, 'not_found', 'No such version.');
    if (this.triggers.references(contentHash) || this.runs.some((r) => r.contentHash === contentHash)) {
      return failure(409, 'in_use', 'A trigger or a run still refers to the version.');
    }
    this.artifacts.delete(contentHash);
    return answer(204);
  }

  /** `PUT .../unsafe-execution`: whether this pipeline of this version may run although UNSAFE. */
  private setUnsafeExecution(
    { caller, body }: ApiRequest,
    contentHash: string,
    pipeline: string,
  ): ApiAnswer {
    let allow: unknown;
    try {
      allow = JSON.parse(body.toString('utf8'))?.allow;
    } catch {
      allow = undefined;
    }
    if (typeof allow !== 'boolean') {
      return failure(400, 'bad_request', 'The body must be {"allow": true or false}.');
    }
    const definition = this.definitionOf(contentHash, pipeline);
    if (!definition) return failure(404, 'definition_not_found', 'No such version and pipeline.');
    definition.allowUnsafeExecution = allow;
    definition.unsafeSetBy = caller.name;
    definition.unsafeSetAt = new Date().toISOString();
    return answer(200, {
      contentHash,
      pipeline,
      allow,
      setBy: definition.unsafeSetBy,
      setAt: definition.unsafeSetAt,
    });
  }

  // ---- runs ----------------------------------------------------------------------------------

  private runDoc(run: FakeRun) {
    const { owner: _owner, log: _log, ...doc } = run;
    return doc;
  }

  private createRun({ caller, body }: ApiRequest): ApiAnswer {
    let request: { contentHash?: unknown; pipeline?: unknown; parameters?: unknown };
    try {
      request = JSON.parse(body.toString('utf8'));
    } catch {
      return failure(400, 'bad_request', 'The body must be JSON.');
    }
    const { contentHash, pipeline, parameters = {} } = request ?? {};
    if (
      typeof contentHash !== 'string' ||
      typeof pipeline !== 'string' ||
      typeof parameters !== 'object' ||
      parameters === null ||
      Object.values(parameters).some((v) => typeof v !== 'string')
    ) {
      return failure(
        400,
        'bad_request',
        'The body must have contentHash, pipeline and parameters.',
      );
    }
    const made = this.attempt({
      contentHash,
      pipeline,
      parameters: parameters as Record<string, string>,
      source: { kind: 'MANUAL', name: caller.name },
      owner: caller.name,
      mayUse: (uploadedBy) => this.sees(caller, uploadedBy),
    });
    return 'run' in made
      ? answer(201, this.runDoc(made.run), { Location: `/api/v1/runs/${made.run.runId}` })
      : made.refusal;
  }

  /** The definition of [pipeline] in the version [contentHash], if there is one. */
  definitionOf(contentHash: string, pipeline: string): Definition | undefined {
    return this.artifacts.get(contentHash)?.definitions.find((d) => d.name === pipeline);
  }

  /** The run that a firing of [trigger] makes, or why it is refused: an admin's, so any version. */
  runForTrigger(trigger: FakeTrigger): { run: FakeRun } | { refusal: ApiAnswer } {
    return this.attempt({
      contentHash: trigger.contentHash,
      pipeline: trigger.pipeline,
      parameters: trigger.parameters,
      source: { kind: 'TRIGGER', name: trigger.name },
      // A developer sees the runs that triggers make of the versions they uploaded.
      owner: null,
      mayUse: () => true,
    });
  }

  /** The checks of a new run, in the Engine's order, and the run when they hold. */
  private attempt(spec: {
    contentHash: string;
    pipeline: string;
    parameters: Record<string, string>;
    source: FakeRun['source'];
    owner: string | null;
    mayUse: (uploadedBy: string) => boolean;
  }): { run: FakeRun } | { refusal: ApiAnswer } {
    const { contentHash, pipeline, parameters: supplied } = spec;
    const artifact = this.artifacts.get(contentHash);
    const definition = artifact?.definitions.find((d) => d.name === pipeline);
    if (!artifact || !definition || !spec.mayUse(artifact.uploadedBy)) {
      return { refusal: failure(404, 'definition_not_found', 'No such version and pipeline.') };
    }

    const declared = definition.metadata.parameters;
    const problems = [
      ...Object.keys(supplied)
        .filter((name) => !declared.some((p) => p.name === name))
        .map((name) => ({ name, problem: 'undeclared' })),
      ...declared
        .filter((p) => p.required && (p.default ?? null) === null && !(p.name in supplied))
        .map((p) => ({ name: p.name, problem: 'missing' })),
    ];
    if (problems.length > 0) {
      return {
        refusal: failure(422, 'invalid_parameters', 'The parameters do not match.', { problems }),
      };
    }
    if (definition.verdict === 'UNSAFE' && !definition.allowUnsafeExecution) {
      return {
        refusal: failure(409, 'unsafe_not_allowed', `${pipeline} is UNSAFE and not allowed.`),
      };
    }
    const unavailable = definition.metadata.resources
      .filter((name) => this.resources.enabledOf(name) !== true)
      .map((resource) => ({
        resource,
        problem: this.resources.enabledOf(resource) === undefined ? 'unknown' : 'disabled',
      }));
    if (unavailable.length > 0) {
      return {
        refusal: failure(409, 'resources_unavailable', 'A shared resource is not available.', {
          problems: unavailable,
        }),
      };
    }

    const effective = Object.fromEntries(
      declared.map((p) => [p.name, supplied[p.name] ?? (p.default as string)]),
    );
    const run: FakeRun = {
      runId: randomUUID(),
      owner: spec.owner ?? artifact.uploadedBy,
      state: 'QUEUED',
      contentHash,
      pipeline,
      className: definition.className,
      source: spec.source,
      parameters: effective,
      createdAt: new Date().toISOString(),
      startedAt: null,
      finishedAt: null,
      failure: null,
      unsafeExecution:
        definition.verdict === 'UNSAFE' && definition.unsafeSetBy
          ? { setBy: definition.unsafeSetBy, setAt: definition.unsafeSetAt! }
          : null,
      log: [],
    };
    this.runs.push(run);
    if (this.autoRun) this.later(this.startDelayMs, () => this.begin(run));
    return { run };
  }

  private find(caller: FakeCallerRef, runId: string): FakeRun | null {
    const run = UUID.test(runId) ? this.runs.find((r) => r.runId === runId) : undefined;
    return run && this.sees(caller, run.owner) ? run : null;
  }

  private readRun({ caller }: ApiRequest, runId: string): ApiAnswer {
    const run = this.find(caller, runId);
    return run ? answer(200, this.runDoc(run)) : failure(404, 'run_not_found', 'No such run.');
  }

  private listRuns({ caller, query }: ApiRequest): ApiAnswer {
    const asked = Number(query.get('limit'));
    const limit = Number.isInteger(asked) && asked > 0 ? Math.min(asked, 200) : 50;
    const pipeline = query.get('pipeline');
    const runs = this.runs
      .filter((r) => this.sees(caller, r.owner) && (pipeline === null || r.pipeline === pipeline))
      .reverse()
      .slice(0, limit)
      .map((r) => this.runDoc(r));
    return answer(200, { runs });
  }

  private cancel({ caller }: ApiRequest, runId: string): ApiAnswer {
    const run = this.find(caller, runId);
    if (!run) return failure(404, 'run_not_found', 'No such run.');
    if (TERMINAL.includes(run.state)) {
      return failure(409, 'already_finished', `The run has finished already (${run.state}).`);
    }
    const started = run.startedAt !== null;
    this.finish(
      run,
      'CANCELLED',
      started
        ? {
            type: 'java.lang.InterruptedException',
            message: 'sleep interrupted',
            trace:
              'java.lang.InterruptedException: sleep interrupted\n\tat java.base/java.lang.Thread.sleep(Thread.java)',
          }
        : null,
    );
    return answer(started ? 202 : 200, {
      runId: run.runId,
      state: run.state,
      cancellation: started ? 'requested' : 'cancelled',
    });
  }

  private readLog({ caller, query }: ApiRequest, runId: string): ApiAnswer {
    const run = this.find(caller, runId);
    if (!run) return failure(404, 'run_not_found', 'No such run.');
    const after = Math.max(0, Number.parseInt(query.get('after') ?? '0', 10) || 0);
    const asked = Number.parseInt(query.get('limit') ?? '', 10);
    const limit = Number.isInteger(asked) && asked > 0 ? Math.min(asked, 2000) : 500;
    const entries = run.log.filter((e) => e.seq > after).slice(0, limit);
    return answer(200, { entries, last: entries.at(-1)?.seq ?? after });
  }

  // ---- what a test arranges ------------------------------------------------------------------

  /** A version that is there already, uploaded by [uploadedBy], with [pipelines]. */
  seedArtifact(
    uploadedBy: string,
    pipelines: FakePipeline[],
    options: { contentHash?: string; uploadedAt?: string } = {},
  ): string {
    const contentHash =
      options.contentHash ?? createHash('sha256').update(randomUUID()).digest('hex');
    this.artifacts.set(contentHash, {
      contentHash,
      sizeBytes: 1000,
      uploadedBy,
      uploadedAt: options.uploadedAt ?? new Date().toISOString(),
      definitions: pipelines.map((p) => this.describe(p)),
    });
    return contentHash;
  }

  /** An admin has allowed the pipeline to run although it is unsafe. */
  allowUnsafe(contentHash: string, pipeline: string, setBy = 'root') {
    const definition = this.artifacts
      .get(contentHash)
      ?.definitions.find((d) => d.name === pipeline);
    if (!definition) throw new Error(`no pipeline ${pipeline} in ${contentHash}`);
    definition.allowUnsafeExecution = true;
    definition.unsafeSetBy = setBy;
    definition.unsafeSetAt = new Date().toISOString();
  }

  /** A run as it is, made by [owner]: for the screens that must show every state. */
  seedRun(owner: string, run: Partial<Omit<FakeRun, 'owner'>> = {}): FakeRun {
    const made: FakeRun = {
      runId: randomUUID(),
      owner,
      state: 'SUCCEEDED',
      contentHash: '0'.repeat(64),
      pipeline: 'demo-slow',
      className: 'samples.slow.SlowPipeline',
      source: { kind: 'MANUAL', name: owner },
      parameters: {},
      createdAt: new Date().toISOString(),
      startedAt: null,
      finishedAt: null,
      failure: null,
      unsafeExecution: null,
      log: [],
      ...run,
    };
    this.runs.push(made);
    return made;
  }

  /** Writes lines into the log of the run, in order, the next sequence numbers. */
  write(runId: string, ...lines: Array<string | { stream: 'STDOUT' | 'STDERR'; line: string }>) {
    const run = this.runs.find((r) => r.runId === runId);
    if (!run) throw new Error(`no run ${runId}`);
    for (const item of lines) {
      const { stream, line } =
        typeof item === 'string' ? { stream: 'STDOUT' as const, line: item } : item;
      run.log.push({
        seq: (run.log.at(-1)?.seq ?? 0) + 1,
        at: new Date().toISOString(),
        stream,
        line,
      });
    }
  }

  /** Moves the run to [state] (finished, if the state is final). */
  setState(runId: string, state: RunState, failureOf: FakeFailure | null = null) {
    const run = this.runs.find((r) => r.runId === runId);
    if (!run) throw new Error(`no run ${runId}`);
    if (TERMINAL.includes(state)) this.finish(run, state, failureOf);
    else {
      run.state = state;
      if (state === 'RUNNING') run.startedAt ??= new Date().toISOString();
    }
  }

  /** The retention has cleaned the log of the run (the run stays). */
  purgeLog(runId: string) {
    const run = this.runs.find((r) => r.runId === runId);
    if (run) run.log = [];
  }

  /** The retention has cleaned the run away: it is a 404 from now on. */
  purgeRun(runId: string) {
    const at = this.runs.findIndex((r) => r.runId === runId);
    if (at >= 0) this.runs.splice(at, 1);
  }

  /** A run that holds the shared resource, as it is. */
  seedHolder(resource: string, runId: string, pipeline: string) {
    this.resources.seedHolder(resource, runId, pipeline);
  }

  /** A run that waits for the shared resource, as it is. */
  seedWaiter(resource: string, runId: string, pipeline: string) {
    this.resources.seedWaiter(resource, runId, pipeline);
  }

  /** A shared resource that an admin has defined. */
  defineResource(name: string, enabled = true, capacity = 1) {
    if (!RESOURCE_NAME.test(name)) throw new Error('not a resource name');
    this.resources.define(name, { enabled, capacity });
  }

  // ---- the sample pipelines, run ---------------------------------------------------------------

  private later(ms: number, action: () => void) {
    const timer = setTimeout(() => {
      this.timers.delete(timer);
      action();
    }, ms);
    this.timers.add(timer);
  }

  private finish(run: FakeRun, state: RunState, failureOf: FakeFailure | null) {
    run.state = state;
    run.finishedAt = new Date().toISOString();
    run.failure = failureOf;
    this.resources.releaseAll(run.runId);
  }

  private begin(run: FakeRun) {
    if (run.state !== 'QUEUED') return; // cancelled while it waited
    const needs = this.definitionOf(run.contentHash, run.pipeline)?.metadata.resources ?? [];
    if (needs.length === 0) return this.start(run);
    const outcome = this.resources.acquire(run, needs, {
      granted: () => this.start(run),
      refused: () =>
        this.finish(run, 'FAILED', {
          type: 'ResourceUnavailable',
          message: 'A shared resource the run waited for was disabled.',
          trace: '',
        }),
    });
    if (outcome === 'waiting') run.state = 'WAITING_FOR_RESOURCES';
  }

  /** The run holds what it needs: it starts, and the sample pipelines do what they do. */
  private start(run: FakeRun) {
    run.state = 'RUNNING';
    run.startedAt = new Date().toISOString();
    // A run that was cancelled writes nothing more and does not end again.
    const going = () => run.state === 'RUNNING';
    const say = (line: string, stream: 'STDOUT' | 'STDERR' = 'STDOUT') => {
      if (going()) this.write(run.runId, { stream, line });
    };

    if (run.pipeline === 'demo-failing') {
      say('preparing');
      this.later(20, () => {
        if (!going()) return;
        say('about to fail');
        const reason = run.parameters.reason ?? '';
        this.finish(run, 'FAILED', {
          type: 'java.lang.IllegalStateException',
          message: reason,
          trace: `java.lang.IllegalStateException: ${reason}\n\tat samples.failing.FailingPipeline.run(FailingPipeline.kt:20)`,
        });
      });
      return;
    }
    if (run.pipeline === 'demo-slow') {
      const label = run.parameters.label ?? 'demo';
      const steps = Number(run.parameters.steps ?? 30);
      const delay = Number(run.parameters.delayMillis ?? 1000);
      say(`[${label}] starting ${steps} steps, ${delay}ms each`);
      let step = 0;
      const next = () => {
        if (!going()) return;
        step += 1;
        say(`[${label}] step ${step}/${steps} done`);
        if (step % 10 === 0)
          say(`[${label}] checkpoint at step ${step} (written to stderr)`, 'STDERR');
        if (step >= steps) {
          say(`[${label}] finished`);
          this.finish(run, 'SUCCEEDED', null);
        } else this.later(delay, next);
      };
      if (steps <= 0) {
        say(`[${label}] finished`);
        this.finish(run, 'SUCCEEDED', null);
      } else this.later(delay, next);
      return;
    }
    if (run.pipeline === 'demo-resource') {
      say('holding demo-printer for 20 seconds');
      this.later(this.resourceHoldMs, () => {
        if (!going()) return;
        say('released');
        this.finish(run, 'SUCCEEDED', null);
      });
      return;
    }
    say(`${run.pipeline} ran`);
    this.finish(run, 'SUCCEEDED', null);
  }

  /** What the analysis of a pipeline of a jar of the Fake comes to under the allow-list in force. */
  private describe(pipeline: FakePipeline): Definition {
    const given = (pipeline.reasons ?? []).map((r) => ({
      kind: r.kind,
      category: r.category ?? null,
      className: r.className ?? null,
      member: r.member ?? null,
      path: r.path ?? [],
      detail: r.detail ?? null,
    }));
    const references = [
      ...given
        .filter((r) => r.kind === 'NOT_ALLOW_LISTED' && r.className !== null)
        .map((r) => ({ className: r.className!, path: r.path })),
      ...(pipeline.references ?? []).map((className) => ({
        className,
        path: [pipeline.className, className],
      })),
    ];
    const definition: Definition = {
      className: pipeline.className,
      name: pipeline.name,
      metadata: {
        parameters: (pipeline.parameters ?? []).map((p) => ({
          name: p.name,
          required: p.required,
          default: p.default ?? null,
        })),
        files: pipeline.files ?? [],
        network: { unrestricted: pipeline.networkUnrestricted ?? false, allow: [] },
        processes: { unrestricted: pipeline.processesUnrestricted ?? false, allow: [] },
        resources: pipeline.resources ?? [],
      },
      verdict: 'SAFE',
      reasons: [],
      fixedReasons: given.filter((r) => r.kind !== 'NOT_ALLOW_LISTED'),
      references,
      allowListVersion: String(this.allowList.current.version),
      allowUnsafeExecution: false,
      unsafeSetBy: null,
      unsafeSetAt: null,
    };
    // What the author gave as reasons stands as it was given; the references are judged.
    const judged = judge(definition, this.allowList.entries);
    const uncovered = judged.reasons.filter(
      (r) => r.kind === 'NOT_ALLOW_LISTED' && !given.some((g) => g.kind === r.kind && g.className === r.className),
    );
    definition.reasons = [...given, ...uncovered];
    definition.verdict = definition.reasons.length > 0 ? 'UNSAFE' : 'SAFE';
    return definition;
  }

  /** Every pipeline of every version, with the hash of the version. */
  allDefinitions(): Array<{ contentHash: string; definition: Definition }> {
    return [...this.artifacts.values()].flatMap((a) =>
      a.definitions.map((definition) => ({ contentHash: a.contentHash, definition })),
    );
  }
}
