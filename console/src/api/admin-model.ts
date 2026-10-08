// What the admin calls of the API answer (08-api.md: Trigger, Allow-list, Shared resources, the
// setting of unsafe execution), as the Console holds it. As in model.ts, the answers are read here
// and nowhere else, and one that is not what the document says is a failure. The values of the
// enumerations (`kind`, `outcome`, `action`, `problem`) are kept as they came: one that a newer
// Engine adds is shown as it is, not refused.

import { bool, list, num, obj, str, strOrNull, strings, type Obj } from './parse.ts';

export interface Trigger {
  name: string;
  /** `cron` or `webhook`. */
  kind: string;
  contentHash: string;
  /** Whose version of the content the trigger is bound to. */
  uploader: string;
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
  /** Whose version of the content: each version is judged and told on its own. */
  uploader: string;
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

/** The result of a check of a resource's entity: a category when it failed, never a reason. */
export interface CheckResult {
  ok: boolean;
  /** `root_unavailable`, `connection_failed`, `alias_missing`, ...; null when it passed. */
  failure: string | null;
  checkedAt: string;
}

/** A pipeline definition that declares a resource: the version, the pipeline and what it expects. */
export interface DeclaringDefinition {
  contentHash: string;
  uploader: string;
  pipeline: string;
  /** The type the definition expects; null when it declares only the name. */
  declaredType: string | null;
  /** The triggers bound to this definition. */
  triggers: number;
}

export interface Resource {
  name: string;
  /** `counter`, `file`, `jdbc-pool` or `openai-compatible` (ADR-019). */
  type: string;
  capacity: number;
  enabled: boolean;
  /** The type's settings that are not secret, as the Engine wrote them. */
  settings: Record<string, unknown>;
  /** The alias of the secret in the keystore; never a secret value. */
  secretAlias: string | null;
  /** `not_set`, `found`, `missing`, `invalid_secret` or `wrong_type`. */
  secretStatus: string;
  /** Each trusted certificate alias of the settings with its status (WI-52), in order. */
  trustStatus: Array<{ alias: string; status: string }>;
  /** The status of the client certificate alias (WI-52): `not_set` when there is none. */
  clientCertStatus: string;
  /**
   * The most requests its entity is asked at once: the capacity times what one holder may do at
   * once (`jdbc-pool`: the size of the pool); null for the types that cannot say.
   */
  concurrencyLimit: number | null;
  /**
   * The use of the type's own, as numbers by name: `activeConnections` of a `jdbc-pool`,
   * `inFlightRequests` of an `openai-compatible` service; null for the other types.
   */
  usage: Record<string, number> | null;
  lastCheck: CheckResult | null;
  declaredBy: { count: number; triggers: number; definitions: DeclaringDefinition[] };
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
  holders: Holder[];
  waiters: Waiter[];
}

/** What deleting a resource would touch (`preview=true`); nothing has been changed. */
export interface RemovalPreview {
  resource: string;
  definitions: number;
  triggers: number;
  holders: number;
  waiters: number;
  inUse: boolean;
}

/** What may be shown of a certificate of the keystore (WI-52): never anything of a key. */
export interface Certificate {
  subject: string;
  notAfter: string;
  /** Whole days to `notAfter`; negative once it has passed. */
  daysLeft: number;
  /** SHA-256, as `keytool -list` prints it. */
  fingerprint: string;
  /** `valid`, `expiring` (fewer days left than the Engine's threshold) or `expired`. */
  expiry: string;
}

/** An alias of the keystore: what it is and which resources use it; never its value. */
export interface Secret {
  alias: string;
  /** `secret`, `trusted_certificate` or `private_key`. */
  type: string;
  /** `found`, `invalid_secret` or `invalid_key`. */
  status: string;
  usedBy: string[];
  /** The certificate of a certificate entry, the chain of a private key entry, its own first; none for a secret. */
  certificates: Certificate[];
}

/** What a reload of the keystore found: how many aliases, and those that changed. */
export interface SecretReload {
  aliases: number;
  changed: Array<{ alias: string; usedBy: string[] }>;
}

export interface Release {
  resource: string;
  runId: string;
  pipeline: string;
  heldSince: string;
}

export interface UnsafeSetting {
  contentHash: string;
  uploader: string;
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
    uploader: str(r.uploader, 'trigger uploader'),
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
          uploader: str(change.uploader, 'uploader'),
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

export function parseCheck(json: unknown): CheckResult {
  const r = obj(json, 'a check');
  return {
    ok: bool(r.ok, 'check ok'),
    failure: strOrNull(r.failure, 'check failure'),
    checkedAt: str(r.checkedAt, 'check checkedAt'),
  };
}

function readDeclaredBy(value: unknown): Resource['declaredBy'] {
  const r = obj(value, 'resource declaredBy');
  return {
    count: num(r.count, 'declaredBy count'),
    triggers: num(r.triggers, 'declaredBy triggers'),
    definitions: list(r.definitions, 'declaredBy definitions', (d) => {
      const definition = obj(d, 'a declaring definition');
      return {
        contentHash: str(definition.contentHash, 'declaring contentHash'),
        uploader: str(definition.uploader, 'declaring uploader'),
        pipeline: str(definition.pipeline, 'declaring pipeline'),
        declaredType: strOrNull(definition.declaredType, 'declaring declaredType'),
        triggers: num(definition.triggers, 'declaring triggers'),
      };
    }),
  };
}

export function parseResource(json: unknown): Resource {
  const r = obj(json, 'a shared resource');
  return {
    name: str(r.name, 'resource name'),
    type: str(r.type, 'resource type'),
    capacity: num(r.capacity, 'resource capacity'),
    enabled: bool(r.enabled, 'resource enabled'),
    settings: obj(r.settings, 'resource settings'),
    secretAlias: strOrNull(r.secretAlias, 'resource secretAlias'),
    secretStatus: str(r.secretStatus, 'resource secretStatus'),
    trustStatus: list(r.trustStatus ?? [], 'resource trustStatus', (v) => {
      const status = obj(v, 'a trust alias status');
      return { alias: str(status.alias, 'trust alias'), status: str(status.status, 'trust status') };
    }),
    clientCertStatus: str(r.clientCertStatus ?? 'not_set', 'resource clientCertStatus'),
    concurrencyLimit:
      r.concurrencyLimit === null || r.concurrencyLimit === undefined
        ? null
        : num(r.concurrencyLimit, 'resource concurrencyLimit'),
    usage:
      r.usage === null || r.usage === undefined
        ? null
        : Object.fromEntries(
            Object.entries(obj(r.usage, 'resource usage')).map(([key, value]) => [key, num(value, 'resource usage')]),
          ),
    lastCheck: r.lastCheck === null || r.lastCheck === undefined ? null : parseCheck(r.lastCheck),
    declaredBy: readDeclaredBy(r.declaredBy),
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

export function parseRemovalPreview(json: unknown): RemovalPreview {
  const r = obj(json, 'a preview of deleting a resource');
  return {
    resource: str(r.resource, 'preview resource'),
    definitions: num(r.definitions, 'preview definitions'),
    triggers: num(r.triggers, 'preview triggers'),
    holders: num(r.holders, 'preview holders'),
    waiters: num(r.waiters, 'preview waiters'),
    inUse: bool(r.inUse, 'preview inUse'),
  };
}

export function parseSecrets(json: unknown): Secret[] {
  return list(obj(json, 'the secrets').secrets, 'secrets', (v) => {
    const r = obj(v, 'a secret');
    return {
      alias: str(r.alias, 'secret alias'),
      type: str(r.type, 'secret type'),
      status: str(r.status, 'secret status'),
      usedBy: strings(r.usedBy, 'secret usedBy'),
      certificates: list(r.certificates ?? [], 'secret certificates', (c) => {
        const certificate = obj(c, 'a certificate');
        return {
          subject: str(certificate.subject, 'certificate subject'),
          notAfter: str(certificate.notAfter, 'certificate notAfter'),
          daysLeft: num(certificate.daysLeft, 'certificate daysLeft'),
          fingerprint: str(certificate.fingerprint, 'certificate fingerprint'),
          expiry: str(certificate.expiry, 'certificate expiry'),
        };
      }),
    };
  });
}

export function parseSecretReload(json: unknown): SecretReload {
  const r = obj(json, 'a reload of the keystore');
  return {
    aliases: num(r.aliases, 'reload aliases'),
    changed: list(r.changed, 'reload changed', (v) => {
      const changed = obj(v, 'a changed alias');
      return { alias: str(changed.alias, 'changed alias'), usedBy: strings(changed.usedBy, 'changed usedBy') };
    }),
  };
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
    uploader: str(r.uploader, 'uploader'),
    pipeline: str(r.pipeline, 'pipeline'),
    allow: bool(r.allow, 'allow'),
    setBy: str(r.setBy, 'setBy'),
    setAt: str(r.setAt, 'setAt'),
  };
}
