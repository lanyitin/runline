// What the API of the pipelines answers (08-api.md: upload, definitions, runs, log), as the Console
// holds it. The answers are read here and nowhere else: one that is not what the document says is a
// failure (`ApiFailure` with status 0), not a screen that breaks half way. The values of the
// enumerations (`state`, `verdict`, `stream`, the kinds of reasons) are kept as they came: one that
// a newer Engine adds is shown as it is (i18n/enums.ts), not refused.

import { bool, list, num, obj, str, strOrNull, strings, type Obj } from './parse.ts';

export interface ParameterSpec {
  name: string;
  required: boolean;
  default: string | null;
}

export interface Metadata {
  parameters: ParameterSpec[];
  files: Array<{ scope: string; mode: string }>;
  network: { unrestricted: boolean; allow: string[] };
  processes: { unrestricted: boolean; allow: string[] };
  resources: string[];
}

export interface Reason {
  kind: string;
  category: string | null;
  className: string | null;
  member: string | null;
  /** From the pipeline to the reference that makes it unsafe. */
  path: string[];
  detail: string | null;
}

export interface Warning {
  kind: string;
  resource: string;
  /** The Engine's own words: not translated, so the Console says the warning by its kind. */
  message: string;
}

export interface Pipeline {
  className: string;
  name: string;
  metadata: Metadata;
  verdict: string;
  reasons: Reason[];
  allowListVersion: string;
  allowUnsafeExecution: boolean;
  warnings: Warning[];
}

/** A pipeline in a listing, with the version it is in. */
export interface Definition extends Pipeline {
  contentHash: string;
  uploadedBy: string;
  uploadedAt: string;
}

export interface Artifact {
  contentHash: string;
  sizeBytes: number;
  uploadedBy: string;
  uploadedAt: string;
  pipelines: Pipeline[];
  limitations: string;
}

export interface RunFailure {
  type: string;
  message: string | null;
  trace: string;
}

export interface Run {
  runId: string;
  state: string;
  contentHash: string;
  pipeline: string;
  className: string;
  source: { kind: string; name: string };
  parameters: Record<string, string>;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  failure: RunFailure | null;
  unsafeExecution: { setBy: string; setAt: string } | null;
}

export interface LogEntry {
  seq: number;
  at: string;
  stream: string;
  line: string;
}

export interface LogPage {
  entries: LogEntry[];
  /** The `after` to go on from. */
  last: number;
}

export interface Cancellation {
  runId: string;
  state: string;
  cancellation: string;
}

const access = (value: unknown, what: string) => {
  const record = obj(value, what);
  return { unrestricted: bool(record.unrestricted, what), allow: strings(record.allow, what) };
};

function parseMetadata(value: unknown): Metadata {
  const record = obj(value, 'metadata');
  return {
    parameters: list(record.parameters, 'metadata.parameters', (p) => {
      const parameter = obj(p, 'a parameter');
      return {
        name: str(parameter.name, 'parameter name'),
        required: bool(parameter.required, 'parameter required'),
        default: strOrNull(parameter.default, 'parameter default'),
      };
    }),
    files: list(record.files, 'metadata.files', (f) => {
      const file = obj(f, 'a file access');
      return { scope: str(file.scope, 'file scope'), mode: str(file.mode, 'file mode') };
    }),
    network: access(record.network, 'metadata.network'),
    processes: access(record.processes, 'metadata.processes'),
    resources: strings(record.resources, 'metadata.resources'),
  };
}

function parsePipelineFields(record: Obj): Pipeline {
  return {
    className: str(record.className, 'className'),
    name: str(record.name, 'name'),
    metadata: parseMetadata(record.metadata),
    verdict: str(record.verdict, 'verdict'),
    reasons: list(record.reasons, 'reasons', (r) => {
      const reason = obj(r, 'a reason');
      return {
        kind: str(reason.kind, 'reason kind'),
        category: strOrNull(reason.category, 'reason category'),
        className: strOrNull(reason.className, 'reason className'),
        member: strOrNull(reason.member, 'reason member'),
        path: strings(reason.path, 'reason path'),
        detail: strOrNull(reason.detail, 'reason detail'),
      };
    }),
    allowListVersion: str(record.allowListVersion, 'allowListVersion'),
    allowUnsafeExecution: bool(record.allowUnsafeExecution, 'allowUnsafeExecution'),
    warnings: list(record.warnings, 'warnings', (w) => {
      const warning = obj(w, 'a warning');
      return {
        kind: str(warning.kind, 'warning kind'),
        resource: str(warning.resource, 'warning resource'),
        message: str(warning.message, 'warning message'),
      };
    }),
  };
}

export function parseArtifact(json: unknown): Artifact {
  const record = obj(json, 'the version');
  return {
    contentHash: str(record.contentHash, 'contentHash'),
    sizeBytes: num(record.sizeBytes, 'sizeBytes'),
    uploadedBy: str(record.uploadedBy, 'uploadedBy'),
    uploadedAt: str(record.uploadedAt, 'uploadedAt'),
    pipelines: list(record.pipelines, 'pipelines', (p) =>
      parsePipelineFields(obj(p, 'a pipeline')),
    ),
    limitations: typeof record.limitations === 'string' ? record.limitations : '',
  };
}

export function parseDefinitions(json: unknown): {
  definitions: Definition[];
  limitations: string;
} {
  const record = obj(json, 'the definitions');
  return {
    definitions: list(record.definitions, 'definitions', (d) => {
      const definition = obj(d, 'a definition');
      return {
        contentHash: str(definition.contentHash, 'contentHash'),
        uploadedBy: str(definition.uploadedBy, 'uploadedBy'),
        uploadedAt: str(definition.uploadedAt, 'uploadedAt'),
        ...parsePipelineFields(definition),
      };
    }),
    limitations: typeof record.limitations === 'string' ? record.limitations : '',
  };
}

export function parseRun(json: unknown): Run {
  const record = obj(json, 'a run');
  const source = obj(record.source, 'run source');
  const parameters = obj(record.parameters ?? {}, 'run parameters');
  const failure =
    record.failure === null || record.failure === undefined
      ? null
      : obj(record.failure, 'run failure');
  const unsafe =
    record.unsafeExecution === null || record.unsafeExecution === undefined
      ? null
      : obj(record.unsafeExecution, 'run unsafeExecution');
  return {
    runId: str(record.runId, 'runId'),
    state: str(record.state, 'state'),
    contentHash: str(record.contentHash, 'contentHash'),
    pipeline: str(record.pipeline, 'pipeline'),
    className: str(record.className, 'className'),
    source: { kind: str(source.kind, 'source kind'), name: str(source.name, 'source name') },
    parameters: Object.fromEntries(
      Object.entries(parameters).map(([name, value]) => [name, str(value, 'a parameter value')]),
    ),
    createdAt: str(record.createdAt, 'createdAt'),
    startedAt: strOrNull(record.startedAt, 'startedAt'),
    finishedAt: strOrNull(record.finishedAt, 'finishedAt'),
    failure: failure && {
      type: str(failure.type, 'failure type'),
      message: strOrNull(failure.message, 'failure message'),
      trace: typeof failure.trace === 'string' ? failure.trace : '',
    },
    unsafeExecution: unsafe && {
      setBy: str(unsafe.setBy, 'unsafeExecution setBy'),
      setAt: str(unsafe.setAt, 'unsafeExecution setAt'),
    },
  };
}

export function parseRuns(json: unknown): Run[] {
  return list(obj(json, 'the runs').runs, 'runs', parseRun);
}

export function parseLog(json: unknown): LogPage {
  const record = obj(json, 'the log');
  return {
    entries: list(record.entries, 'entries', (e) => {
      const entry = obj(e, 'a log entry');
      return {
        seq: num(entry.seq, 'seq'),
        at: str(entry.at, 'at'),
        stream: str(entry.stream, 'stream'),
        line: str(entry.line, 'line'),
      };
    }),
    last: num(record.last, 'last'),
  };
}

export function parseCancellation(json: unknown): Cancellation {
  const record = obj(json, 'the cancellation');
  return {
    runId: str(record.runId, 'runId'),
    state: str(record.state, 'state'),
    cancellation: str(record.cancellation, 'cancellation'),
  };
}
