// The shared resources of the Fake Engine (08-api.md: Shared resources, ADR-007, ADR-019): what an
// admin defines (a type, settings that are not secret, the alias of a secret in the keystore), who
// holds each one and who waits for it, the definitions that declare it, its last check, and
// deleting it. A run that declares resources holds them from the start of the run to its end and
// waits while one is not free, in the order of arrival. The contract tests
// (contract/admin-contract.ts) hold it to the Engine.

import { answer, failure, jsonObject, type ApiAnswer, type ApiRequest, type FakeRoute } from './fake-api';
import { TYPE_RULES } from './fake-resource-settings';

export interface FakeResource {
  name: string;
  /** One of the closed set (ADR-019); see `create` for what the Fake defines through the API. */
  type: string;
  capacity: number;
  /** The type's settings that are not secret: `{}` for a counter. */
  settings: Record<string, unknown>;
  /** The alias of the secret in the keystore, in its lower-case form; null for none. */
  secretAlias: string | null;
  enabled: boolean;
  /** The last check: null when never checked, or when the settings or alias changed since. */
  lastCheck: CheckResult | null;
  /**
   * What the entity behind the resource answers to a check, as a failure category, null when it
   * is fine. The Fake has no file, service or database behind a resource: a test says what is
   * there. A counter has no entity.
   */
  entityFailure: string | null;
  /**
   * The use of the type's own (08-api `usage`): connections held by runs of a `jdbc-pool`, requests
   * in flight to an `openai-compatible` service; null for the other types. The Fake has no pool and
   * no service: a test says how much is in use.
   */
  usage: Record<string, number> | null;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
}

interface Holder {
  runId: string;
  pipeline: string;
  since: string;
}

interface Waiter {
  runId: string;
  pipeline: string;
  names: string[];
  since: string;
  granted: () => void;
  refused: () => void;
}

export interface CheckResult {
  ok: boolean;
  /** The category of the failure; null when the check passed. */
  failure: string | null;
  checkedAt: string;
}

/** A pipeline definition that declares a resource, as `declaredBy` lists it. */
export interface DeclaringDefinition {
  contentHash: string;
  uploader: string;
  pipeline: string;
  /** The type the definition expects; null when it declares only the name. */
  declaredType: string | null;
  /** The triggers bound to this definition. */
  triggers: number;
}

/** What the resources ask of the rest of the Fake Engine: who declares a resource. */
export interface ResourceDeclarations {
  declarersOf(name: string): DeclaringDefinition[];
}

/** What the resources ask of the keystore: the status of an alias, undefined when it has none. */
export interface ResourceSecrets {
  statusOf(alias: string): string | undefined;
}

/** The closed set of types (ADR-019). */
const TYPES = ['counter', 'file', 'jdbc-pool', 'openai-compatible'];

const NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

/** The use of each type that has one, while nothing uses it. */
const USAGE: Record<string, Record<string, number>> = {
  'jdbc-pool': { activeConnections: 0 },
  'openai-compatible': { inFlightRequests: 0 },
};

/** The setting that says what one holder may do at once, of each type that has a limit of it. */
const PER_RUN: Record<string, string> = {
  'jdbc-pool': 'connectionsPerRun',
  'openai-compatible': 'requestsPerRun',
};

const seconds = (since: string) => Math.max(0, Date.now() - Date.parse(since)) / 1000;

export class FakeResources {
  constructor(
    private readonly declarations: ResourceDeclarations,
    private readonly secrets: ResourceSecrets,
  ) {}

  private readonly resources = new Map<string, FakeResource>();
  private readonly holders = new Map<string, Holder[]>();
  private waiters: Waiter[] = [];

  /** Whether the resource is enabled; undefined when it is not defined. */
  enabledOf(name: string): boolean | undefined {
    return this.resources.get(name)?.enabled;
  }

  /** The type of the resource; undefined when it is not defined. */
  typeOf(name: string): string | undefined {
    return this.resources.get(name)?.type;
  }

  define(
    name: string,
    options: {
      enabled?: boolean;
      capacity?: number;
      by?: string;
      type?: string;
      settings?: Record<string, unknown>;
      secretAlias?: string | null;
      entityFailure?: string | null;
      lastCheck?: CheckResult | null;
      usage?: Record<string, number>;
    } = {},
  ) {
    const type = options.type ?? 'counter';
    const at = new Date().toISOString();
    const by = options.by ?? 'root';
    this.resources.set(name, {
      name,
      type,
      capacity: options.capacity ?? 1,
      settings: options.settings ?? {},
      secretAlias: options.secretAlias?.toLowerCase() ?? null,
      lastCheck: options.lastCheck ?? null,
      entityFailure: options.entityFailure ?? null,
      usage: options.usage ?? USAGE[type] ?? null,
      enabled: options.enabled ?? true,
      createdBy: by,
      createdAt: at,
      updatedBy: by,
      updatedAt: at,
    });
  }

  /** The resources that refer to [alias], by name, in order. */
  usersOf(alias: string): string[] {
    return [...this.resources.values()]
      .filter((r) => r.secretAlias === alias)
      .map((r) => r.name)
      .sort();
  }

  /** What is in use of [name] now, as its entity would say (the Fake has none). */
  setUsage(name: string, usage: Record<string, number>) {
    this.resources.get(name)!.usage = usage;
  }

  /** A run that holds [name] as it is, for the screens that must show every state. */
  seedHolder(name: string, runId: string, pipeline: string) {
    this.hold({ runId, pipeline }, [name]);
  }

  /** A run that waits for [name] as it is. */
  seedWaiter(name: string, runId: string, pipeline: string) {
    this.waiters.push({
      runId,
      pipeline,
      names: [name],
      since: new Date().toISOString(),
      granted: () => undefined,
      refused: () => undefined,
    });
  }

  readonly routes: FakeRoute[] = [
    { method: 'POST', pattern: /^\/api\/v1\/resources$/, admin: true, handle: (r) => this.create(r) },
    { method: 'GET', pattern: /^\/api\/v1\/resources$/, admin: true, handle: () => this.list() },
    {
      method: 'GET',
      pattern: /^\/api\/v1\/resources\/([^/]+)$/,
      admin: true,
      handle: (_r, m) => this.read(decodeURIComponent(m[1])),
    },
    {
      method: 'PATCH',
      pattern: /^\/api\/v1\/resources\/([^/]+)$/,
      admin: true,
      handle: (r, m) => this.update(r, decodeURIComponent(m[1])),
    },
    {
      method: 'DELETE',
      pattern: /^\/api\/v1\/resources\/([^/]+)$/,
      admin: true,
      handle: (r, m) => this.remove(r, decodeURIComponent(m[1])),
    },
    {
      method: 'POST',
      pattern: /^\/api\/v1\/resources\/([^/]+)\/check$/,
      admin: true,
      handle: (_r, m) => this.check(decodeURIComponent(m[1])),
    },
    {
      method: 'POST',
      pattern: /^\/api\/v1\/resources\/([^/]+)\/holders\/([^/]+)\/release$/,
      admin: true,
      handle: (_r, m) => this.forceRelease(decodeURIComponent(m[1]), decodeURIComponent(m[2])),
    },
  ];

  // ---- what the runs do ------------------------------------------------------------------------

  /**
   * A run that needs [names] asks for them: [granted] is called when it holds them all (at once if
   * they are free), [refused] when one of them is disabled or gone. Whether it had to wait is the
   * answer.
   */
  acquire(
    run: { runId: string; pipeline: string },
    names: string[],
    callbacks: { granted: () => void; refused: () => void },
  ): 'granted' | 'waiting' | 'refused' {
    if (names.some((n) => this.enabledOf(n) !== true)) {
      callbacks.refused();
      return 'refused';
    }
    const waiting = this.waiters.some((w) => w.names.some((n) => names.includes(n)));
    if (!waiting && this.free(names)) {
      this.hold(run, names);
      callbacks.granted();
      return 'granted';
    }
    this.waiters.push({
      runId: run.runId,
      pipeline: run.pipeline,
      names,
      since: new Date().toISOString(),
      ...callbacks,
    });
    return 'waiting';
  }

  /** The run has ended (or was cancelled): it holds nothing and waits for nothing. */
  releaseAll(runId: string) {
    this.waiters = this.waiters.filter((w) => w.runId !== runId);
    for (const [name, list] of this.holders) {
      this.holders.set(
        name,
        list.filter((h) => h.runId !== runId),
      );
    }
    this.serve();
  }

  private free(names: string[]): boolean {
    return names.every(
      (n) => (this.holders.get(n)?.length ?? 0) < (this.resources.get(n)?.capacity ?? 0),
    );
  }

  private hold(run: { runId: string; pipeline: string }, names: string[]) {
    const since = new Date().toISOString();
    for (const name of names) {
      this.holders.set(name, [
        ...(this.holders.get(name) ?? []),
        { runId: run.runId, pipeline: run.pipeline, since },
      ]);
    }
  }

  /** Gives what is free to the runs that wait, in the order they came. */
  private serve() {
    for (const waiter of [...this.waiters]) {
      // A run that was started by the one before it may have changed who waits.
      if (!this.waiters.includes(waiter) || !this.free(waiter.names)) continue;
      this.waiters = this.waiters.filter((w) => w !== waiter);
      this.hold(waiter, waiter.names);
      waiter.granted();
    }
  }

  // ---- the documents ---------------------------------------------------------------------------

  private secretStatusOf(resource: FakeResource): string {
    if (resource.secretAlias === null) return 'not_set';
    return this.secrets.statusOf(resource.secretAlias) ?? 'missing';
  }

  private doc(resource: FakeResource) {
    const { entityFailure: _entity, ...shown } = resource;
    return {
      ...shown,
      secretStatus: this.secretStatusOf(resource),
      concurrencyLimit: this.concurrencyLimitOf(resource),
      declaredBy: this.declaredBy(resource.name),
      holders: (this.holders.get(resource.name) ?? []).map((h) => ({
        runId: h.runId,
        pipeline: h.pipeline,
        heldSince: h.since,
        heldSeconds: seconds(h.since),
      })),
      waiters: this.waiters
        .filter((w) => w.names.includes(resource.name))
        .map((w) => ({
          runId: w.runId,
          pipeline: w.pipeline,
          waitingFor: w.names.filter((n) => !this.free([n])),
          waitingSince: w.since,
          waitedSeconds: seconds(w.since),
        })),
    };
  }

  /** The capacity times what one holder may do at once (1 unless set), for the types that say. */
  private concurrencyLimitOf(resource: FakeResource): number | null {
    const perRun = PER_RUN[resource.type];
    if (perRun === undefined) return null;
    const value = resource.settings[perRun];
    return resource.capacity * (typeof value === 'number' ? value : 1);
  }

  private declaredBy(name: string) {
    const definitions = this.declarations.declarersOf(name);
    return {
      count: definitions.length,
      triggers: definitions.reduce((sum, d) => sum + d.triggers, 0),
      definitions,
    };
  }

  private notFound = () => failure(404, 'resource_not_found', 'No such shared resource.');
  private invalid = (message: string, problem?: string) =>
    failure(422, 'invalid_resource', message, problem === undefined ? {} : { problem });

  // ---- the calls -------------------------------------------------------------------------------

  private create({ caller, body }: ApiRequest): ApiAnswer {
    const request = jsonObject(body);
    if (!request || typeof request.name !== 'string' || typeof request.capacity !== 'number') {
      return failure(400, 'bad_request', 'The body needs a name and a capacity.');
    }
    if (!NAME.test(request.name)) return this.invalid('The name is not valid.', 'name');
    if (!Number.isInteger(request.capacity) || request.capacity < 1) {
      return this.invalid('The capacity must be an integer of one or more.', 'capacity');
    }
    const type = request.type ?? 'counter';
    if (typeof type !== 'string' || !TYPES.includes(type)) {
      return this.invalid('The type is not one of the closed set.', 'unknown_type');
    }
    const rules = TYPE_RULES[type];
    if (rules === undefined) {
      // The Engine makes every type of the closed set (WI-43, WI-46, WI-48); a type the Fake has no
      // rules for is one a test seeds.
      return this.invalid(`The Fake does not define ${type} through the API.`, 'unsupported_type');
    }
    const settings = rules.settings(request.settings);
    if ('problem' in settings) return this.invalid('The settings are not valid.', settings.problem);
    const alias = request.secretAlias;
    if (alias !== undefined && (!rules.takesSecret || typeof alias !== 'string' || !NAME.test(alias))) {
      return this.invalid('The secret alias is not valid.', 'invalid_secret_alias');
    }
    if (this.resources.has(request.name)) {
      return failure(409, 'resource_exists', `The resource ${request.name} exists.`);
    }
    this.define(request.name, {
      capacity: request.capacity,
      by: caller.name,
      type,
      settings: settings.settings,
      secretAlias: (alias as string | undefined) ?? null,
    });
    return answer(201, this.doc(this.resources.get(request.name)!), {
      Location: `/api/v1/resources/${request.name}`,
    });
  }

  private list(): ApiAnswer {
    return answer(200, { resources: [...this.resources.values()].map((r) => this.doc(r)) });
  }

  private read(name: string): ApiAnswer {
    const resource = this.resources.get(name);
    return resource ? answer(200, this.doc(resource)) : this.notFound();
  }

  private update({ caller, body }: ApiRequest, name: string): ApiAnswer {
    const request = jsonObject(body);
    if (
      !request ||
      (request.capacity !== undefined && typeof request.capacity !== 'number') ||
      (request.enabled !== undefined && typeof request.enabled !== 'boolean') ||
      (request.name !== undefined && typeof request.name !== 'string') ||
      (request.type !== undefined && typeof request.type !== 'string') ||
      (request.settings !== undefined &&
        (typeof request.settings !== 'object' || request.settings === null || Array.isArray(request.settings))) ||
      (request.secretAlias !== undefined && typeof request.secretAlias !== 'string')
    ) {
      return failure(400, 'bad_request', 'The body may have capacity, enabled, settings and secretAlias.');
    }
    if (request.name !== undefined) return this.invalid('The name cannot be changed.', 'immutable_name');
    if (request.type !== undefined) return this.invalid('The type cannot be changed.', 'immutable_type');
    const { capacity, enabled, settings, secretAlias } = request;
    if ([capacity, enabled, settings, secretAlias].every((value) => value === undefined)) {
      return this.invalid('Give something to change.', 'nothing_to_change');
    }
    if (capacity !== undefined && (!Number.isInteger(capacity) || (capacity as number) < 1)) {
      return this.invalid('The capacity must be an integer of one or more.', 'capacity');
    }
    const resource = this.resources.get(name);
    if (!resource) return this.notFound();
    // The settings replace the stored ones as a whole, and are checked as when it was defined; so is
    // the alias, against the type.
    const rules = TYPE_RULES[resource.type];
    const written = settings === undefined || rules === undefined ? undefined : rules.settings(settings);
    if (written !== undefined && 'problem' in written) return this.invalid('The settings are not valid.', written.problem);
    if (secretAlias !== undefined && (rules?.takesSecret !== true || !NAME.test(secretAlias as string))) {
      return this.invalid('The secret alias is not valid.', 'invalid_secret_alias');
    }
    if (written !== undefined) resource.settings = written.settings;
    if (secretAlias !== undefined) resource.secretAlias = (secretAlias as string).toLowerCase();
    if (settings !== undefined || secretAlias !== undefined) resource.lastCheck = null;
    if (request.capacity !== undefined) resource.capacity = request.capacity as number;
    if (request.enabled !== undefined) resource.enabled = request.enabled as boolean;
    resource.updatedBy = caller.name;
    resource.updatedAt = new Date().toISOString();
    if (!resource.enabled) {
      // The runs that wait for it fail; the ones that hold it go on.
      for (const waiter of this.waiters.filter((w) => w.names.includes(name))) {
        this.waiters = this.waiters.filter((w) => w !== waiter);
        waiter.refused();
      }
    }
    this.serve();
    return answer(200, this.doc(resource));
  }

  /**
   * Deletes a resource nobody holds or waits for; what declares it does not stop it. With
   * `preview=true`, says what deleting it would touch and changes nothing.
   */
  private remove({ query }: ApiRequest, name: string): ApiAnswer {
    const preview = query.get('preview') ?? 'false';
    if (preview !== 'true' && preview !== 'false') {
      return failure(400, 'bad_request', 'preview must be true or false.');
    }
    const resource = this.resources.get(name);
    if (!resource) return this.notFound();
    const holders = this.holders.get(name)?.length ?? 0;
    const waiters = this.waiters.filter((w) => w.names.includes(name)).length;
    if (preview === 'true') {
      const declaredBy = this.declaredBy(name);
      return answer(200, {
        resource: name,
        definitions: declaredBy.count,
        triggers: declaredBy.triggers,
        holders,
        waiters,
        inUse: holders + waiters > 0,
      });
    }
    if (holders + waiters > 0) {
      return failure(409, 'resource_in_use', `Runs hold or wait for ${name}.`, { holders, waiters });
    }
    this.resources.delete(name);
    this.holders.delete(name);
    return answer(204);
  }

  /**
   * A counter has no entity: its check always passes. A resource whose alias the keystore lacks or
   * cannot use fails without asking the entity; otherwise the entity answers.
   */
  private check(name: string): ApiAnswer {
    const resource = this.resources.get(name);
    if (!resource) return this.notFound();
    const status = this.secretStatusOf(resource);
    const failed =
      status === 'missing' ? 'alias_missing' : status === 'invalid_secret' ? 'alias_invalid' : resource.entityFailure;
    resource.lastCheck = { ok: failed === null, failure: failed, checkedAt: new Date().toISOString() };
    return answer(200, resource.lastCheck);
  }

  private forceRelease(name: string, runId: string): ApiAnswer {
    if (!this.resources.has(name)) return this.notFound();
    const holder = UUID.test(runId)
      ? (this.holders.get(name) ?? []).find((h) => h.runId === runId)
      : undefined;
    if (!holder) {
      return failure(404, 'not_a_holder', `The run does not hold ${name}.`);
    }
    this.holders.set(
      name,
      (this.holders.get(name) ?? []).filter((h) => h !== holder),
    );
    this.serve();
    return answer(200, {
      resource: name,
      runId: holder.runId,
      pipeline: holder.pipeline,
      heldSince: holder.since,
    });
  }
}
