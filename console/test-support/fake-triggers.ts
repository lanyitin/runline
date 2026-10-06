// The triggers of the Fake Engine (08-api.md: Trigger, Webhook entrance): what an admin binds to a
// version of a pipeline, the secret of a webhook (only its hash is kept), and the firings, which
// each delivery of a webhook makes. A cron trigger is not fired by a clock here: a test writes the
// firings it needs (`seedFiring`). The contract tests (contract/admin-contract.ts) hold it to the
// Engine.

import { createHash, randomBytes } from 'node:crypto';
import {
  answer,
  failure,
  isStringMap,
  jsonObject,
  type ApiAnswer,
  type ApiRequest,
  type FakeRoute,
} from './fake-api';
import type { FakeBackend } from './fake-backend';

export interface FakeTrigger {
  name: string;
  kind: 'cron' | 'webhook';
  contentHash: string;
  pipeline: string;
  parameters: Record<string, string>;
  enabled: boolean;
  cron: string | null;
  timeZone: string | null;
  secretHash: string | null;
  secretRotatedAt: string | null;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
}

export interface FakeFiring {
  triggerName: string;
  firedAt: string;
  scheduledFor: string | null;
  deliveryId: string | null;
  outcome: 'run_created' | 'refused' | 'failed' | 'interrupted' | 'pending';
  reason: string | null;
  detail: string | null;
  runId: string | null;
}

const NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;
const DELIVERY_ID = /^[\x21-\x7e]{1,200}$/;

const MONTHS = ['JAN', 'FEB', 'MAR', 'APR', 'MAY', 'JUN', 'JUL', 'AUG', 'SEP', 'OCT', 'NOV', 'DEC'];
const DAYS = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'];

/** Whether [value] is a number or a name of a field, within [min] and [max]. */
function inRange(value: string, min: number, max: number, names: string[] = []): boolean {
  const named = names.indexOf(value.toUpperCase());
  if (named >= 0) return true;
  if (!/^\d+$/.test(value)) return false;
  const number = Number(value);
  return number >= min && number <= max;
}

function validField(field: string, min: number, max: number, names: string[] = []): boolean {
  return field.split(',').every((item) => {
    const [range, step, extra] = item.split('/');
    if (extra !== undefined) return false;
    if (step !== undefined && !/^[1-9]\d*$/.test(step)) return false;
    if (range === '*') return true;
    const [from, to, more] = range.split('-');
    if (more !== undefined) return false;
    return inRange(from, min, max, names) && (to === undefined || inRange(to, min, max, names));
  });
}

/** A standard five-field cron expression. */
export function validCron(expression: string): boolean {
  const fields = expression.trim().split(/\s+/);
  if (fields.length !== 5) return false;
  const [minute, hour, day, month, weekday] = fields;
  return (
    validField(minute, 0, 59) &&
    validField(hour, 0, 23) &&
    validField(day, 1, 31) &&
    validField(month, 1, 12, MONTHS) &&
    validField(weekday, 0, 7, DAYS)
  );
}

function validTimeZone(zone: string): boolean {
  try {
    new Intl.DateTimeFormat('en', { timeZone: zone });
    return true;
  } catch {
    return false;
  }
}

const sha256 = (text: string) => createHash('sha256').update(text).digest('hex');

export class FakeTriggers {
  readonly triggers = new Map<string, FakeTrigger>();
  readonly firings: FakeFiring[] = [];

  constructor(private readonly backend: FakeBackend) {}

  readonly routes: FakeRoute[] = [
    { method: 'POST', pattern: /^\/api\/v1\/triggers$/, admin: true, handle: (r) => this.create(r) },
    { method: 'GET', pattern: /^\/api\/v1\/triggers$/, admin: true, handle: () => this.list() },
    {
      method: 'GET',
      pattern: /^\/api\/v1\/triggers\/([^/]+)$/,
      admin: true,
      handle: (_r, m) => this.read(m[1]),
    },
    {
      method: 'PATCH',
      pattern: /^\/api\/v1\/triggers\/([^/]+)$/,
      admin: true,
      handle: (r, m) => this.change(r, m[1]),
    },
    {
      method: 'DELETE',
      pattern: /^\/api\/v1\/triggers\/([^/]+)$/,
      admin: true,
      handle: (_r, m) => this.remove(m[1]),
    },
    {
      method: 'POST',
      pattern: /^\/api\/v1\/triggers\/([^/]+)\/rotate-secret$/,
      admin: true,
      handle: (r, m) => this.rotate(r, m[1]),
    },
    {
      method: 'GET',
      pattern: /^\/api\/v1\/triggers\/([^/]+)\/firings$/,
      admin: true,
      handle: (r, m) => this.listFirings(r, m[1]),
    },
  ];

  /** A trigger as it is, for the screens that must show what the Engine can hold. */
  seed(trigger: Partial<FakeTrigger> & Pick<FakeTrigger, 'name' | 'contentHash' | 'pipeline'>): FakeTrigger {
    const at = new Date().toISOString();
    const made: FakeTrigger = {
      kind: 'cron',
      parameters: {},
      enabled: true,
      cron: '* * * * *',
      timeZone: 'UTC',
      secretHash: null,
      secretRotatedAt: null,
      createdBy: 'root',
      createdAt: at,
      updatedBy: 'root',
      updatedAt: at,
      ...trigger,
    };
    this.triggers.set(made.name, made);
    return made;
  }

  /** Whether a version is bound by a trigger (it cannot be deleted then). */
  references(contentHash: string): boolean {
    return [...this.triggers.values()].some((t) => t.contentHash === contentHash);
  }

  // ---- the documents ---------------------------------------------------------------------------

  private effective(trigger: FakeTrigger): Record<string, string> {
    const declared =
      this.backend.definitionOf(trigger.contentHash, trigger.pipeline)?.metadata.parameters ?? [];
    return {
      ...Object.fromEntries(
        declared.filter((p) => p.default !== null).map((p) => [p.name, p.default as string]),
      ),
      ...trigger.parameters,
    };
  }

  private doc(trigger: FakeTrigger) {
    return {
      name: trigger.name,
      kind: trigger.kind,
      contentHash: trigger.contentHash,
      pipeline: trigger.pipeline,
      parameters: trigger.parameters,
      effectiveParameters: this.effective(trigger),
      enabled: trigger.enabled,
      cron: trigger.cron,
      timeZone: trigger.timeZone,
      webhookPath: trigger.kind === 'webhook' ? `/api/v1/webhooks/${trigger.name}` : null,
      secretConfigured: trigger.secretHash !== null,
      secretRotatedAt: trigger.secretRotatedAt,
      createdBy: trigger.createdBy,
      createdAt: trigger.createdAt,
      updatedBy: trigger.updatedBy,
      updatedAt: trigger.updatedAt,
    };
  }

  private invalid(problem: string, message: string) {
    return failure(422, 'invalid_trigger', message, { problem });
  }

  private notFound = () => failure(404, 'trigger_not_found', 'No such trigger.');

  /** What is wrong with the parameters of a binding, as the answer of a run that is refused. */
  private checkBinding(
    contentHash: string,
    pipeline: string,
    parameters: Record<string, string>,
  ): ApiAnswer | null {
    const definition = this.backend.definitionOf(contentHash, pipeline);
    if (!definition) return failure(404, 'definition_not_found', 'No such version and pipeline.');
    const declared = definition.metadata.parameters;
    const problems = [
      ...Object.keys(parameters)
        .filter((name) => !declared.some((p) => p.name === name))
        .map((name) => ({ name, problem: 'undeclared' })),
      ...declared
        .filter((p) => p.required && p.default === null && !(p.name in parameters))
        .map((p) => ({ name: p.name, problem: 'missing' })),
    ];
    return problems.length > 0
      ? failure(422, 'invalid_parameters', 'The parameters do not match.', { problems })
      : null;
  }

  // ---- the calls -------------------------------------------------------------------------------

  private create({ caller, body }: ApiRequest): ApiAnswer {
    const request = jsonObject(body);
    if (
      !request ||
      typeof request.name !== 'string' ||
      typeof request.contentHash !== 'string' ||
      typeof request.pipeline !== 'string' ||
      (request.parameters !== undefined && !isStringMap(request.parameters)) ||
      (request.cron !== undefined && typeof request.cron !== 'string') ||
      (request.timeZone !== undefined && typeof request.timeZone !== 'string') ||
      (request.enabled !== undefined && typeof request.enabled !== 'boolean')
    ) {
      return failure(400, 'bad_request', 'The body is not what a trigger needs.');
    }
    if (request.kind !== 'cron' && request.kind !== 'webhook') {
      return failure(400, 'bad_request', 'kind must be cron or webhook.');
    }
    const kind = request.kind;
    if (!NAME.test(request.name)) return this.invalid('name', 'The name is not valid.');
    if (kind === 'webhook' && (request.cron !== undefined || request.timeZone !== undefined)) {
      return this.invalid('schedule_not_allowed', 'A webhook has no schedule.');
    }
    if (kind === 'cron') {
      if (request.cron === undefined) return this.invalid('cron_required', 'A cron is needed.');
      if (!validCron(request.cron)) return this.invalid('cron_expression', 'Not a cron expression.');
      if (request.timeZone !== undefined && !validTimeZone(request.timeZone)) {
        return this.invalid('time_zone', 'Not a time zone.');
      }
    }
    if (this.triggers.has(request.name)) {
      return failure(409, 'trigger_exists', `The trigger ${request.name} exists.`);
    }
    const parameters = (request.parameters as Record<string, string> | undefined) ?? {};
    const refused = this.checkBinding(request.contentHash, request.pipeline, parameters);
    if (refused) return refused;

    const now = new Date().toISOString();
    const secret = kind === 'webhook' ? randomBytes(32).toString('base64url') : null;
    const trigger: FakeTrigger = {
      name: request.name,
      kind,
      contentHash: request.contentHash,
      pipeline: request.pipeline,
      parameters,
      enabled: request.enabled ?? true,
      cron: kind === 'cron' ? (request.cron as string) : null,
      timeZone: kind === 'cron' ? ((request.timeZone as string | undefined) ?? 'UTC') : null,
      secretHash: secret === null ? null : sha256(secret),
      secretRotatedAt: secret === null ? null : now,
      createdBy: caller.name,
      createdAt: now,
      updatedBy: caller.name,
      updatedAt: now,
    };
    this.triggers.set(trigger.name, trigger);
    return answer(201, { trigger: this.doc(trigger), secret }, { Location: `/api/v1/triggers/${trigger.name}` });
  }

  private list(): ApiAnswer {
    return answer(200, { triggers: [...this.triggers.values()].map((t) => this.doc(t)) });
  }

  private read(name: string): ApiAnswer {
    const trigger = this.triggers.get(name);
    return trigger ? answer(200, this.doc(trigger)) : this.notFound();
  }

  private change({ caller, body }: ApiRequest, name: string): ApiAnswer {
    const trigger = this.triggers.get(name);
    if (!trigger) return this.notFound();
    const request = jsonObject(body);
    if (
      !request ||
      (request.contentHash !== undefined && typeof request.contentHash !== 'string') ||
      (request.pipeline !== undefined && typeof request.pipeline !== 'string') ||
      (request.parameters !== undefined && !isStringMap(request.parameters)) ||
      (request.enabled !== undefined && typeof request.enabled !== 'boolean') ||
      (request.cron !== undefined && typeof request.cron !== 'string') ||
      (request.timeZone !== undefined && typeof request.timeZone !== 'string')
    ) {
      return failure(400, 'bad_request', 'The body is not what a change needs.');
    }
    const given = ['contentHash', 'pipeline', 'parameters', 'enabled', 'cron', 'timeZone'].filter(
      (key) => request[key] !== undefined,
    );
    if (given.length === 0) return this.invalid('nothing_to_change', 'Nothing to change.');
    if (trigger.kind === 'webhook' && (request.cron !== undefined || request.timeZone !== undefined)) {
      return this.invalid('schedule_not_allowed', 'A webhook has no schedule.');
    }
    if (request.cron !== undefined && !validCron(request.cron as string)) {
      return this.invalid('cron_expression', 'Not a cron expression.');
    }
    if (request.timeZone !== undefined && !validTimeZone(request.timeZone as string)) {
      return this.invalid('time_zone', 'Not a time zone.');
    }
    const contentHash = (request.contentHash as string | undefined) ?? trigger.contentHash;
    const pipeline = (request.pipeline as string | undefined) ?? trigger.pipeline;
    const parameters = (request.parameters as Record<string, string> | undefined) ?? trigger.parameters;
    const refused = this.checkBinding(contentHash, pipeline, parameters);
    if (refused) return refused;

    trigger.contentHash = contentHash;
    trigger.pipeline = pipeline;
    trigger.parameters = parameters;
    if (request.enabled !== undefined) trigger.enabled = request.enabled as boolean;
    if (request.cron !== undefined) trigger.cron = request.cron as string;
    if (request.timeZone !== undefined) trigger.timeZone = request.timeZone as string;
    trigger.updatedBy = caller.name;
    trigger.updatedAt = new Date().toISOString();
    return answer(200, this.doc(trigger));
  }

  private remove(name: string): ApiAnswer {
    if (!this.triggers.delete(name)) return this.notFound();
    for (let i = this.firings.length - 1; i >= 0; i -= 1) {
      if (this.firings[i].triggerName === name) this.firings.splice(i, 1);
    }
    return answer(204);
  }

  private rotate({ caller }: ApiRequest, name: string): ApiAnswer {
    const trigger = this.triggers.get(name);
    if (!trigger) return this.notFound();
    if (trigger.kind !== 'webhook') {
      return failure(409, 'not_a_webhook', 'Only a webhook has a secret to rotate.');
    }
    const secret = randomBytes(32).toString('base64url');
    const now = new Date().toISOString();
    trigger.secretHash = sha256(secret);
    trigger.secretRotatedAt = now;
    trigger.updatedBy = caller.name;
    trigger.updatedAt = now;
    return answer(200, { trigger: this.doc(trigger), secret });
  }

  private listFirings({ query }: ApiRequest, name: string): ApiAnswer {
    if (!this.triggers.has(name)) return this.notFound();
    const asked = Number(query.get('limit'));
    const limit = Number.isInteger(asked) && asked > 0 ? Math.min(asked, 200) : 50;
    const firings = this.firings
      .filter((f) => f.triggerName === name)
      .reverse() // written in the order they happened: the newest first
      .slice(0, limit)
      .map(({ triggerName: _name, ...firing }) => firing);
    return answer(200, { firings });
  }

  // ---- the webhook entrance --------------------------------------------------------------------

  /** `POST /api/v1/webhooks/{name}`: no token, the secret of the trigger instead. */
  webhook(name: string, secret: string | undefined, deliveryId: string | undefined): ApiAnswer {
    const trigger = this.triggers.get(name);
    if (
      !trigger ||
      trigger.kind !== 'webhook' ||
      !trigger.enabled ||
      secret === undefined ||
      trigger.secretHash !== sha256(secret)
    ) {
      return failure(401, 'unauthorized', 'Not authenticated.');
    }
    if (deliveryId === undefined || !DELIVERY_ID.test(deliveryId)) {
      return failure(400, 'invalid_delivery_id', 'The delivery id is missing or not valid.');
    }
    const known = this.firings.some((f) => f.triggerName === name && f.deliveryId === deliveryId);
    if (!known) this.fire(trigger, { deliveryId, scheduledFor: null });
    return answer(202, { status: 'accepted' });
  }

  /** Makes the run of a firing of [trigger], or the refusal; the firing says which. */
  fire(trigger: FakeTrigger, how: { deliveryId: string | null; scheduledFor: string | null }) {
    const firing: FakeFiring = {
      triggerName: trigger.name,
      firedAt: new Date().toISOString(),
      scheduledFor: how.scheduledFor,
      deliveryId: how.deliveryId,
      outcome: 'pending',
      reason: null,
      detail: null,
      runId: null,
    };
    this.firings.push(firing);
    const made = this.backend.runForTrigger(trigger);
    if ('run' in made) {
      firing.outcome = 'run_created';
      firing.runId = made.run.runId;
    } else {
      firing.outcome = 'refused';
      const body = made.refusal.json as { error: string; message: string };
      firing.reason = body.error;
      firing.detail = body.message;
    }
    return firing;
  }
}
