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

export interface FakeCallerRef {
  name: string;
  role: 'developer' | 'admin';
}

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

interface Definition {
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

export interface ApiRequest {
  method: string;
  /** The path without the query. */
  path: string;
  query: URLSearchParams;
  caller: FakeCallerRef;
  body: Buffer;
}

export interface ApiAnswer {
  status: number;
  headers?: Record<string, string>;
  /** Sent as JSON; none for an answer without a body. */
  json?: unknown;
}

const LIMITATIONS =
  'The analysis only checks the class references of the compiled classes (a Fake).';

const answer = (status: number, json?: unknown, headers?: Record<string, string>): ApiAnswer => ({
  status,
  json,
  headers,
});
const failure = (status: number, error: string, message: string, extra: object = {}) =>
  answer(status, { error, message, ...extra });

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
}

export class FakeBackend {
  readonly artifacts = new Map<string, Artifact>();
  readonly runs: FakeRun[] = [];
  /** The shared resources that are defined, by name: whether each is enabled. */
  readonly resources = new Map<string, boolean>();
  maxUploadBytes: number;
  autoRun: boolean;
  startDelayMs: number;
  private readonly timers = new Set<ReturnType<typeof setTimeout>>();
  /** An answer, once, to the next upload instead of the real one (a refusal the Fake does not make). */
  nextUploadRefusal: ApiAnswer | null = null;

  constructor(options: FakeBackendOptions = {}) {
    this.maxUploadBytes = options.maxUploadBytes ?? 50 * 1024 * 1024;
    this.autoRun = options.autoRun ?? true;
    this.startDelayMs = options.startDelayMs ?? 5;
  }

  stop() {
    for (const timer of this.timers) clearTimeout(timer);
    this.timers.clear();
  }

  // ---- routing -------------------------------------------------------------------------------

  /** Whether [path] is one of this API for [method]: an answer of 401 and not of 404 without a token. */
  handles(method: string, path: string): boolean {
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
      definitions: pipelines.map(describe),
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
        const enabled = this.resources.get(resource);
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
    const artifact = this.artifacts.get(contentHash);
    const definition = artifact?.definitions.find((d) => d.name === pipeline);
    if (!artifact || !definition || !this.sees(caller, artifact.uploadedBy)) {
      return failure(404, 'definition_not_found', 'No such version and pipeline.');
    }

    const supplied = parameters as Record<string, string>;
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
      return failure(422, 'invalid_parameters', 'The parameters do not match.', { problems });
    }
    if (definition.verdict === 'UNSAFE' && !definition.allowUnsafeExecution) {
      return failure(409, 'unsafe_not_allowed', `${pipeline} is UNSAFE and not allowed.`);
    }
    const unavailable = definition.metadata.resources
      .filter((name) => this.resources.get(name) !== true)
      .map((resource) => ({
        resource,
        problem: this.resources.has(resource) ? 'disabled' : 'unknown',
      }));
    if (unavailable.length > 0) {
      return failure(409, 'resources_unavailable', 'A shared resource is not available.', {
        problems: unavailable,
      });
    }

    const effective = Object.fromEntries(
      declared.map((p) => [p.name, supplied[p.name] ?? (p.default as string)]),
    );
    const run: FakeRun = {
      runId: randomUUID(),
      owner: caller.name,
      state: 'QUEUED',
      contentHash,
      pipeline,
      className: definition.className,
      source: { kind: 'MANUAL', name: caller.name },
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
    return answer(201, this.runDoc(run), { Location: `/api/v1/runs/${run.runId}` });
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
      definitions: pipelines.map(describe),
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

  /** A shared resource that an admin has defined. */
  defineResource(name: string, enabled = true) {
    if (!RESOURCE_NAME.test(name)) throw new Error('not a resource name');
    this.resources.set(name, enabled);
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
  }

  private begin(run: FakeRun) {
    if (run.state !== 'QUEUED') return; // cancelled while it waited
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
    say(`${run.pipeline} ran`);
    this.finish(run, 'SUCCEEDED', null);
  }
}

function describe(pipeline: FakePipeline): Definition {
  const reasons = (pipeline.reasons ?? []).map((r) => ({
    kind: r.kind,
    category: r.category ?? null,
    className: r.className ?? null,
    member: r.member ?? null,
    path: r.path ?? [],
    detail: r.detail ?? null,
  }));
  return {
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
    verdict: reasons.length > 0 ? 'UNSAFE' : 'SAFE',
    reasons,
    allowListVersion: '1',
    allowUnsafeExecution: false,
    unsafeSetBy: null,
    unsafeSetAt: null,
  };
}
