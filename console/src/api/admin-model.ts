// What the admin calls of the API answer (08-api.md: Trigger, Allow-list, Shared resources, the
// setting of unsafe execution), as the Console holds it. As in model.ts, the answers are read here
// and nowhere else, and one that is not what the document says is a failure. The values of the
// enumerations (`kind`, `outcome`, `action`, `problem`) are kept as they came: one that a newer
// Engine adds is shown as it is, not refused.

import { bool, list, num, obj, str, strOrNull, type Obj } from './parse.ts';

export interface Trigger {
  name: string;
  /** `cron` or `webhook`. */
  kind: string;
  contentHash: string;
  pipeline: string;
  /** What the admin gave. */
  parameters: Record<string, string>;
  /** What every run gets: those, and the defaults of the version. */
  effectiveParameters: Record<string, string>;
  enabled: boolean;
  cron: string | null;
  timeZone: string | null;
  webhookPath: string | null;
  secretConfigured: boolean;
  secretRotatedAt: string | null;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
}

/** The answer that makes a webhook or rotates its secret: the only one that has the secret. */
export interface TriggerWithSecret {
  trigger: Trigger;
  secret: string | null;
}

export interface Firing {
  firedAt: string;
  scheduledFor: string | null;
  deliveryId: string | null;
  /** `run_created`, `refused`, `failed`, `interrupted` or `pending`. */
  outcome: string;
  reason: string | null;
  detail: string | null;
  runId: string | null;
}

export interface AllowEntry {
  /** `package` or `class`. */
  kind: string;
  name: string;
  /** Of a package: whether it is "this package only". Null for a class. */
  exactOnly: boolean | null;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
}

export interface AllowList {
  version: string;
  changedBy: string;
  changedAt: string;
  entries: AllowEntry[];
  limitations: string;
}

export interface AllowListVersion {
  version: string;
  changedBy: string;
  changedAt: string;
  /** `INITIAL`, `ENTRY_ADDED`, `ENTRY_CHANGED` or `ENTRY_REMOVED`. */
  action: string;
  /** The Engine's own words. */
  detail: string;
  rejudgedDefinitions: number;
  becameUnsafe: number;
  becameSafe: number;
}

export interface VerdictChange {
  contentHash: string;
  pipeline: string;
  className: string;
  from: string;
  to: string;
  /** The setting before the change. */
  allowUnsafeExecution: boolean;
  unsafeExecutionRevoked: boolean;
}

export interface Impact {
  examinedArtifacts: number;
  examinedDefinitions: number;
  becameUnsafe: number;
  becameSafe: number;
  unreadable: number;
  changes: VerdictChange[];
}

/** What a change of the allow-list does, or in a preview would do. */
export interface AllowListChange {
  preview: boolean;
  version: string;
  entry: AllowEntry | null;
  impact: Impact;
  redundantEntries: AllowEntry[];
  limitations: string;
}

export interface Holder {
  runId: string;
  pipeline: string;
  heldSince: string;
  heldSeconds: number;
}

export interface Waiter {
  runId: string;
  pipeline: string;
  waitingFor: string[];
  waitingSince: string;
  waitedSeconds: number;
}

export interface Resource {
  name: string;
  capacity: number;
  enabled: boolean;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
  holders: Holder[];
  waiters: Waiter[];
}

export interface Release {
  resource: string;
  runId: string;
  pipeline: string;
  heldSince: string;
}

export interface UnsafeSetting {
  contentHash: string;
  pipeline: string;
  allow: boolean;
  setBy: string;
  setAt: string;
}

const stringMap = (value: unknown, what: string): Record<string, string> =>
  Object.fromEntries(
    Object.entries(obj(value ?? {}, what)).map(([key, v]) => [key, str(v, what)]),
  );

function readTrigger(value: unknown): Trigger {
  const r = obj(value, 'a trigger');
  return {
    name: str(r.name, 'trigger name'),
    kind: str(r.kind, 'trigger kind'),
    contentHash: str(r.contentHash, 'trigger contentHash'),
    pipeline: str(r.pipeline, 'trigger pipeline'),
    parameters: stringMap(r.parameters, 'trigger parameters'),
    effectiveParameters: stringMap(r.effectiveParameters, 'trigger effectiveParameters'),
    enabled: bool(r.enabled, 'trigger enabled'),
    cron: strOrNull(r.cron, 'trigger cron'),
    timeZone: strOrNull(r.timeZone, 'trigger timeZone'),
    webhookPath: strOrNull(r.webhookPath, 'trigger webhookPath'),
    secretConfigured: bool(r.secretConfigured, 'trigger secretConfigured'),
    secretRotatedAt: strOrNull(r.secretRotatedAt, 'trigger secretRotatedAt'),
    createdBy: str(r.createdBy, 'trigger createdBy'),
    createdAt: str(r.createdAt, 'trigger createdAt'),
    updatedBy: str(r.updatedBy, 'trigger updatedBy'),
    updatedAt: str(r.updatedAt, 'trigger updatedAt'),
  };
}

export const parseTrigger = readTrigger;

export function parseTriggers(json: unknown): Trigger[] {
  return list(obj(json, 'the triggers').triggers, 'triggers', readTrigger);
}

export function parseTriggerWithSecret(json: unknown): TriggerWithSecret {
  const r = obj(json, 'a trigger with its secret');
  return { trigger: readTrigger(r.trigger), secret: strOrNull(r.secret, 'secret') };
}

export function parseFirings(json: unknown): Firing[] {
  return list(obj(json, 'the firings').firings, 'firings', (f) => {
    const r = obj(f, 'a firing');
    return {
      firedAt: str(r.firedAt, 'firedAt'),
      scheduledFor: strOrNull(r.scheduledFor, 'scheduledFor'),
      deliveryId: strOrNull(r.deliveryId, 'deliveryId'),
      outcome: str(r.outcome, 'outcome'),
      reason: strOrNull(r.reason, 'reason'),
      detail: strOrNull(r.detail, 'detail'),
      runId: strOrNull(r.runId, 'runId'),
    };
  });
}

function readEntry(value: unknown): AllowEntry {
  const r = obj(value, 'an allow-list entry');
  const exact = r.exactOnly;
  return {
    kind: str(r.kind, 'entry kind'),
    name: str(r.name, 'entry name'),
    exactOnly: exact === null || exact === undefined ? null : bool(exact, 'entry exactOnly'),
    createdBy: str(r.createdBy, 'entry createdBy'),
    createdAt: str(r.createdAt, 'entry createdAt'),
    updatedBy: str(r.updatedBy, 'entry updatedBy'),
    updatedAt: str(r.updatedAt, 'entry updatedAt'),
  };
}

const limitationsOf = (r: Obj) => (typeof r.limitations === 'string' ? r.limitations : '');

export function parseAllowList(json: unknown): AllowList {
  const r = obj(json, 'the allow-list');
  return {
    version: str(r.version, 'allow-list version'),
    changedBy: str(r.changedBy, 'allow-list changedBy'),
    changedAt: str(r.changedAt, 'allow-list changedAt'),
    entries: list(r.entries, 'allow-list entries', readEntry),
    limitations: limitationsOf(r),
  };
}

export function parseAllowListVersions(json: unknown): AllowListVersion[] {
  return list(obj(json, 'the versions').versions, 'versions', (v) => {
    const r = obj(v, 'an allow-list version');
    return {
      version: str(r.version, 'version'),
      changedBy: str(r.changedBy, 'changedBy'),
      changedAt: str(r.changedAt, 'changedAt'),
      action: str(r.action, 'action'),
      detail: typeof r.detail === 'string' ? r.detail : '',
      rejudgedDefinitions: num(r.rejudgedDefinitions, 'rejudgedDefinitions'),
      becameUnsafe: num(r.becameUnsafe, 'becameUnsafe'),
      becameSafe: num(r.becameSafe, 'becameSafe'),
    };
  });
}

export function parseAllowListChange(json: unknown): AllowListChange {
  const r = obj(json, 'a change of the allow-list');
  const impact = obj(r.impact, 'the impact');
  return {
    preview: bool(r.preview, 'preview'),
    version: str(r.version, 'version'),
    entry: r.entry === null || r.entry === undefined ? null : readEntry(r.entry),
    impact: {
      examinedArtifacts: num(impact.examinedArtifacts, 'examinedArtifacts'),
      examinedDefinitions: num(impact.examinedDefinitions, 'examinedDefinitions'),
      becameUnsafe: num(impact.becameUnsafe, 'becameUnsafe'),
      becameSafe: num(impact.becameSafe, 'becameSafe'),
      unreadable: num(impact.unreadable, 'unreadable'),
      changes: list(impact.changes, 'changes', (c) => {
        const change = obj(c, 'a change of a verdict');
        return {
          contentHash: str(change.contentHash, 'contentHash'),
          pipeline: str(change.pipeline, 'pipeline'),
          className: str(change.className, 'className'),
          from: str(change.from, 'from'),
          to: str(change.to, 'to'),
          allowUnsafeExecution: bool(change.allowUnsafeExecution, 'allowUnsafeExecution'),
          unsafeExecutionRevoked: bool(change.unsafeExecutionRevoked, 'unsafeExecutionRevoked'),
        };
      }),
    },
    redundantEntries: list(r.redundantEntries, 'redundantEntries', readEntry),
    limitations: limitationsOf(r),
  };
}

export function parseResource(json: unknown): Resource {
  const r = obj(json, 'a shared resource');
  return {
    name: str(r.name, 'resource name'),
    capacity: num(r.capacity, 'resource capacity'),
    enabled: bool(r.enabled, 'resource enabled'),
    createdBy: str(r.createdBy, 'resource createdBy'),
    createdAt: str(r.createdAt, 'resource createdAt'),
    updatedBy: str(r.updatedBy, 'resource updatedBy'),
    updatedAt: str(r.updatedAt, 'resource updatedAt'),
    holders: list(r.holders, 'holders', (h) => {
      const holder = obj(h, 'a holder');
      return {
        runId: str(holder.runId, 'holder runId'),
        pipeline: str(holder.pipeline, 'holder pipeline'),
        heldSince: str(holder.heldSince, 'heldSince'),
        heldSeconds: num(holder.heldSeconds, 'heldSeconds'),
      };
    }),
    waiters: list(r.waiters, 'waiters', (w) => {
      const waiter = obj(w, 'a waiter');
      return {
        runId: str(waiter.runId, 'waiter runId'),
        pipeline: str(waiter.pipeline, 'waiter pipeline'),
        waitingFor: list(waiter.waitingFor, 'waitingFor', (n) => str(n, 'waitingFor')),
        waitingSince: str(waiter.waitingSince, 'waitingSince'),
        waitedSeconds: num(waiter.waitedSeconds, 'waitedSeconds'),
      };
    }),
  };
}

export function parseResources(json: unknown): Resource[] {
  return list(obj(json, 'the resources').resources, 'resources', parseResource);
}

export function parseRelease(json: unknown): Release {
  const r = obj(json, 'a release');
  return {
    resource: str(r.resource, 'resource'),
    runId: str(r.runId, 'runId'),
    pipeline: str(r.pipeline, 'pipeline'),
    heldSince: str(r.heldSince, 'heldSince'),
  };
}

export function parseUnsafeSetting(json: unknown): UnsafeSetting {
  const r = obj(json, 'the setting of unsafe execution');
  return {
    contentHash: str(r.contentHash, 'contentHash'),
    pipeline: str(r.pipeline, 'pipeline'),
    allow: bool(r.allow, 'allow'),
    setBy: str(r.setBy, 'setBy'),
    setAt: str(r.setAt, 'setAt'),
  };
}
