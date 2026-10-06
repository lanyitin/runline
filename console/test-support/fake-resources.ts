// The shared resources of the Fake Engine (08-api.md: Shared resources, ADR-007): what an admin
// defines, who holds each one and who waits for it. A run that declares resources holds them from
// the start of the run to its end and waits while one is not free, in the order of arrival. The
// contract tests (contract/admin-contract.ts) hold it to the Engine.

import { answer, failure, jsonObject, type ApiAnswer, type ApiRequest, type FakeRoute } from './fake-api';

export interface FakeResource {
  name: string;
  capacity: number;
  enabled: boolean;
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

const NAME = /^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

const seconds = (since: string) => Math.max(0, Date.now() - Date.parse(since)) / 1000;

export class FakeResources {
  private readonly resources = new Map<string, FakeResource>();
  private readonly holders = new Map<string, Holder[]>();
  private waiters: Waiter[] = [];

  /** Whether the resource is enabled; undefined when it is not defined. */
  enabledOf(name: string): boolean | undefined {
    return this.resources.get(name)?.enabled;
  }

  define(name: string, options: { enabled?: boolean; capacity?: number; by?: string } = {}) {
    const at = new Date().toISOString();
    const by = options.by ?? 'root';
    this.resources.set(name, {
      name,
      capacity: options.capacity ?? 1,
      enabled: options.enabled ?? true,
      createdBy: by,
      createdAt: at,
      updatedBy: by,
      updatedAt: at,
    });
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

  private doc(resource: FakeResource) {
    return {
      ...resource,
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

  private notFound = () => failure(404, 'resource_not_found', 'No such shared resource.');
  private invalid = (message: string) => failure(422, 'invalid_resource', message);

  // ---- the calls -------------------------------------------------------------------------------

  private create({ caller, body }: ApiRequest): ApiAnswer {
    const request = jsonObject(body);
    if (!request || typeof request.name !== 'string' || typeof request.capacity !== 'number') {
      return failure(400, 'bad_request', 'The body needs a name and a capacity.');
    }
    if (!NAME.test(request.name)) return this.invalid('The name is not valid.');
    if (!Number.isInteger(request.capacity) || request.capacity < 1) {
      return this.invalid('The capacity must be an integer of one or more.');
    }
    if (this.resources.has(request.name)) {
      return failure(409, 'resource_exists', `The resource ${request.name} exists.`);
    }
    this.define(request.name, { capacity: request.capacity, by: caller.name });
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
    const resource = this.resources.get(name);
    if (!resource) return this.notFound();
    const request = jsonObject(body);
    if (
      !request ||
      (request.capacity !== undefined && typeof request.capacity !== 'number') ||
      (request.enabled !== undefined && typeof request.enabled !== 'boolean')
    ) {
      return failure(400, 'bad_request', 'The body may have capacity and enabled.');
    }
    if (request.capacity === undefined && request.enabled === undefined) {
      return this.invalid('Give a capacity or enabled.');
    }
    if (
      request.capacity !== undefined &&
      (!Number.isInteger(request.capacity) || (request.capacity as number) < 1)
    ) {
      return this.invalid('The capacity must be an integer of one or more.');
    }
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
