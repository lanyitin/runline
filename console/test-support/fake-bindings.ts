// The resource roles and bindings of the Fake Engine (08-api.md: Resource roles and bindings,
// ADR-025): which resource each role of a pipeline is behind, for each uploader and pipeline name,
// and how a role comes to a resource (the bound one, or the one of its own name). It is the one
// place that says what is wrong with a role, so that the warnings of a pipeline, the refusal of a
// run, the page of the roles and the resources' list of who uses them cannot disagree. The contract
// tests (contract/bindings-contract.ts) hold it to the Engine.

import {
  answer,
  ambiguousVersion,
  failure,
  jsonObject,
  type ApiAnswer,
  type ApiRequest,
  type FakeCallerRef,
  type FakeRoute,
} from './fake-api';
import type { Definition, FakeBackend } from './fake-backend';

export interface FakeBinding {
  resource: string;
  setBy: string;
  setAt: string;
}

/** What is wrong with a role: it has no resource, or the one it comes to cannot be used. */
export type RoleProblem = 'unbound' | 'unknown' | 'disabled' | 'type_mismatch';

/** The closed set of the types of shared resources (ADR-019). */
const TYPES = ['counter', 'file', 'jdbc-pool', 'openai-compatible'];

const DEFINITION_NOT_FOUND = () =>
  failure(404, 'definition_not_found', 'No such version and pipeline.');

export interface ResolvedRole {
  role: string;
  /** The type the pipeline expects of the resource; null when it declares only the name. */
  type: string | null;
  purpose: string | null;
  binding: FakeBinding | null;
  /** The resource the role comes to: the bound one, or the one of its own name. */
  resource: string;
  isDefault: boolean;
  exists: boolean;
  enabled: boolean | null;
  resourceType: string | null;
  typeMatches: boolean | null;
  problem: RoleProblem | null;
}

export class FakeBindings {
  /** The bindings, by uploader and pipeline name, then by role. */
  private readonly bound = new Map<string, Map<string, FakeBinding>>();

  constructor(private readonly backend: FakeBackend) {}

  private key = (uploader: string, pipeline: string) => `${uploader}\u0000${pipeline}`;

  private of(uploader: string, pipeline: string): Map<string, FakeBinding> {
    const found = this.bound.get(this.key(uploader, pipeline));
    if (found) return found;
    const made = new Map<string, FakeBinding>();
    this.bound.set(this.key(uploader, pipeline), made);
    return made;
  }

  /** A binding that is there already, as a test arranges it. */
  seed(uploader: string, pipeline: string, role: string, resource: string, setBy = uploader) {
    this.of(uploader, pipeline).set(role, { resource, setBy, setAt: new Date().toISOString() });
  }

  /** The roles of [definition], as the uploader's bindings and the resources stand now. */
  rolesOf(uploader: string, definition: Definition): ResolvedRole[] {
    const bindings = this.of(uploader, definition.name);
    const { resources: roles, resourceTypes, resourcePurposes } = definition.metadata;
    return roles.map((role) => {
      const binding = bindings.get(role) ?? null;
      const resource = binding?.resource ?? role;
      const enabled = this.backend.resources.enabledOf(resource);
      const resourceType = this.backend.resources.typeOf(resource) ?? null;
      const type = resourceTypes[role] ?? null;
      const exists = enabled !== undefined;
      const typeMatches = !exists ? null : type === null || type === resourceType;
      const problem: RoleProblem | null = !exists
        ? binding
          ? 'unknown'
          : 'unbound'
        : !enabled
          ? 'disabled'
          : typeMatches === false
            ? 'type_mismatch'
            : null;
      return {
        role,
        type,
        purpose: resourcePurposes[role] ?? null,
        binding,
        resource,
        isDefault: binding === null,
        exists,
        enabled: exists ? enabled : null,
        resourceType,
        typeMatches,
        problem,
      };
    });
  }

  /** The roles of the uploader's pipeline that are bound but that [definition] does not declare. */
  staleOf(uploader: string, definition: Definition) {
    return [...this.of(uploader, definition.name)]
      .filter(([role]) => !definition.metadata.resources.includes(role))
      .map(([role, binding]) => ({ role, ...binding }));
  }

  /** The resources a role of the type may be bound to, by name; all of them when it has no type. */
  private candidates(type: string | null) {
    return this.backend.resources
      .all()
      .filter((r) => type === null || r.type === type)
      .map((r) => ({ name: r.name, enabled: r.enabled }))
      .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0));
  }

  /** What a role of a run's pipeline comes to, that cannot be used; one entry for each such role. */
  unavailable(uploader: string, definition: Definition) {
    return this.rolesOf(uploader, definition)
      .filter((r) => r.problem !== null)
      .map((r) => ({ role: r.role, resource: r.resource, problem: r.problem }));
  }

  /** The resources the roles of the pipeline come to, each once (a run takes each only once). */
  resourcesOf(uploader: string, definition: Definition): string[] {
    return [...new Set(this.rolesOf(uploader, definition).map((r) => r.resource))];
  }

  /** The warnings that are about a role, as the Engine says them: about the resource it comes to. */
  warningsOf(uploader: string, definition: Definition) {
    const warning = (kind: string, resource: string, message: string) => ({ kind, resource, message });
    const { resourceTypes } = definition.metadata;
    const outside = (type: string | undefined) => type !== undefined && !TYPES.includes(type);
    return [
      ...this.rolesOf(uploader, definition).flatMap((r) => {
        if (r.problem === 'unbound' || r.problem === 'unknown') {
          return [warning('resource_unknown', r.resource, `The resource ${r.resource} is not defined (role ${r.role}).`)];
        }
        if (r.problem === 'disabled') {
          return [warning('resource_disabled', r.resource, `The resource ${r.resource} is disabled (role ${r.role}).`)];
        }
        if (r.problem === 'type_mismatch' && !outside(r.type ?? undefined)) {
          return [
            warning('resource_type_mismatch', r.resource, `The resource ${r.resource} is not of the type declared for ${r.role}.`),
          ];
        }
        return [];
      }),
      ...Object.entries(resourceTypes)
        .filter(([, type]) => outside(type))
        .map(([role]) =>
          warning('resource_type_unknown', role, `The type declared for ${role} is not one of the set.`),
        ),
    ];
  }

  /** The definitions that come to the resource [name] through a role, with the triggers on each. */
  declarersOf(name: string) {
    return this.backend.allDefinitions().flatMap(({ contentHash, uploader, definition }) =>
      this.rolesOf(uploader, definition)
        .filter((r) => r.resource === name)
        .map((r) => ({
          contentHash,
          uploader,
          pipeline: definition.name,
          role: r.role,
          declaredType: r.type,
          triggers: this.backend.triggersOf(contentHash, uploader, definition.name),
        })),
    );
  }

  // ---- the calls -------------------------------------------------------------------------------

  readonly routes: FakeRoute[] = [
    {
      method: 'GET',
      pattern: /^\/api\/v1\/definitions\/([^/]+)\/([^/]+)\/resource-bindings$/,
      admin: false,
      handle: (r, m) => this.read(r, decodeURIComponent(m[1]), decodeURIComponent(m[2])),
    },
    {
      method: 'PUT',
      pattern: /^\/api\/v1\/definitions\/([^/]+)\/([^/]+)\/resource-bindings\/([^/]+)$/,
      admin: false,
      handle: (r, m) => this.put(r, decodeURIComponent(m[1]), decodeURIComponent(m[2]), decodeURIComponent(m[3])),
    },
    {
      method: 'DELETE',
      pattern: /^\/api\/v1\/definitions\/([^/]+)\/([^/]+)\/resource-bindings\/([^/]+)$/,
      admin: false,
      handle: (r, m) => this.remove(r, decodeURIComponent(m[1]), decodeURIComponent(m[2]), decodeURIComponent(m[3])),
    },
  ];

  /** The version and pipeline a request means for the caller, or why it is refused. */
  private target(
    { caller, query }: ApiRequest,
    contentHash: string,
    pipeline: string,
  ): { uploader: string; definition: Definition } | { refusal: ApiAnswer } {
    const found = this.backend.resolve(contentHash, query.get('uploader'), (u) => mayUse(caller, u));
    if ('ambiguous' in found) return { refusal: ambiguousVersion(found.ambiguous) };
    if ('none' in found) return { refusal: DEFINITION_NOT_FOUND() };
    const definition = found.version.definitions.find((d) => d.name === pipeline);
    if (!definition) return { refusal: DEFINITION_NOT_FOUND() };
    return { uploader: found.version.uploadedBy, definition };
  }

  private doc(request: ApiRequest, contentHash: string, uploader: string, definition: Definition) {
    return {
      contentHash,
      uploader,
      pipeline: definition.name,
      roles: this.rolesOf(uploader, definition).map((r) => ({
        ...r,
        candidates: this.candidates(r.type),
      })),
      uploaders: this.backend
        .versionsOf(contentHash)
        .map((v) => v.uploadedBy)
        .filter((u) => mayUse(request.caller, u)),
      staleBindings: this.staleOf(uploader, definition),
    };
  }

  private read(request: ApiRequest, contentHash: string, pipeline: string): ApiAnswer {
    const target = this.target(request, contentHash, pipeline);
    if ('refusal' in target) return target.refusal;
    return answer(200, this.doc(request, contentHash, target.uploader, target.definition));
  }

  private put(request: ApiRequest, contentHash: string, pipeline: string, role: string): ApiAnswer {
    const target = this.target(request, contentHash, pipeline);
    if ('refusal' in target) return target.refusal;
    const body = jsonObject(request.body);
    if (!body || typeof body.resource !== 'string') {
      return failure(400, 'bad_request', 'The body must be {"resource": "name"}.');
    }
    const declared = target.definition.metadata;
    if (!declared.resources.includes(role)) {
      return failure(422, 'invalid_binding', 'The version does not declare this role.', {
        problem: 'unknown_role',
      });
    }
    const type = this.backend.resources.typeOf(body.resource);
    if (type === undefined) {
      return failure(404, 'resource_not_found', 'No such shared resource.');
    }
    const expected = declared.resourceTypes[role];
    if (expected !== undefined && expected !== type) {
      return failure(422, 'invalid_binding', 'The resource is not of the type of the role.', {
        problem: 'type_mismatch',
      });
    }
    this.of(target.uploader, pipeline).set(role, {
      resource: body.resource,
      setBy: request.caller.name,
      setAt: new Date().toISOString(),
    });
    return answer(200, this.doc(request, contentHash, target.uploader, target.definition));
  }

  private remove(request: ApiRequest, contentHash: string, pipeline: string, role: string): ApiAnswer {
    const target = this.target(request, contentHash, pipeline);
    if ('refusal' in target) return target.refusal;
    const bindings = this.of(target.uploader, pipeline);
    if (!target.definition.metadata.resources.includes(role) && !bindings.has(role)) {
      return failure(404, 'role_not_found', 'No such role.');
    }
    bindings.delete(role);
    return answer(200, this.doc(request, contentHash, target.uploader, target.definition));
  }
}

/** A developer may use the versions they uploaded; an admin, any. */
const mayUse = (caller: FakeCallerRef, uploader: string) =>
  caller.role === 'admin' || caller.name === uploader;
