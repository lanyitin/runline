// What the API of the resource roles and bindings answers (08-api.md, ADR-025): the roles of a
// pipeline, what each is bound to, the state of the resource it comes to and the resources it may
// be bound to. Read here and nowhere else, as model.ts does for the rest. The values of the
// enumerations (`problem`, the resource's type) are kept as they came: one a newer Engine adds is
// shown as it is, not refused.

import { bool, list, obj, str, strOrNull, type Obj } from './parse.ts';

export interface Binding {
  resource: string;
  setBy: string;
  setAt: string;
}

export interface Role {
  role: string;
  /** The type the pipeline expects of the resource; null when it declares only the name. */
  type: string | null;
  /** What the resource is for, as the jar says it: untrusted text. */
  purpose: string | null;
  /** The binding that was made; null when there is none. */
  binding: Binding | null;
  /** The resource the role comes to: the bound one, or the one of the role's own name. */
  resource: string;
  /** Whether the role takes the resource of its own name for want of a binding. */
  isDefault: boolean;
  exists: boolean;
  /** Null when the resource is not defined. */
  enabled: boolean | null;
  resourceType: string | null;
  /** Null when the resource is not defined. */
  typeMatches: boolean | null;
  /** `unbound`, `unknown`, `disabled`, `type_mismatch`, or null when a run can use it. */
  problem: string | null;
  /** The resources the role may be bound to: of its type, the disabled ones among them. */
  candidates: Array<{ name: string; enabled: boolean }>;
}

export interface StaleBinding extends Binding {
  role: string;
}

export interface ResourceBindings {
  contentHash: string;
  /** Whose bindings these are. */
  uploader: string;
  pipeline: string;
  roles: Role[];
  /** Whose versions of the content the caller may see: an admin chooses among them. */
  uploaders: string[];
  /** Bound roles that this version no longer declares. */
  staleBindings: StaleBinding[];
}

function readBinding(value: unknown, what: string): Binding {
  const record = obj(value, what);
  return {
    resource: str(record.resource, `${what} resource`),
    setBy: str(record.setBy, `${what} setBy`),
    setAt: str(record.setAt, `${what} setAt`),
  };
}

function readRole(value: unknown): Role {
  const r: Obj = obj(value, 'a role');
  return {
    role: str(r.role, 'role'),
    type: strOrNull(r.type, 'role type'),
    purpose: strOrNull(r.purpose, 'role purpose'),
    binding: r.binding === null || r.binding === undefined ? null : readBinding(r.binding, 'role binding'),
    resource: str(r.resource, 'role resource'),
    isDefault: bool(r.isDefault, 'role isDefault'),
    exists: bool(r.exists, 'role exists'),
    enabled: r.enabled === null || r.enabled === undefined ? null : bool(r.enabled, 'role enabled'),
    resourceType: strOrNull(r.resourceType, 'role resourceType'),
    typeMatches:
      r.typeMatches === null || r.typeMatches === undefined ? null : bool(r.typeMatches, 'role typeMatches'),
    problem: strOrNull(r.problem, 'role problem'),
    candidates: list(r.candidates, 'role candidates', (c) => {
      const candidate = obj(c, 'a candidate');
      return { name: str(candidate.name, 'candidate name'), enabled: bool(candidate.enabled, 'candidate enabled') };
    }),
  };
}

export function parseResourceBindings(json: unknown): ResourceBindings {
  const r = obj(json, 'the resource bindings');
  return {
    contentHash: str(r.contentHash, 'contentHash'),
    uploader: str(r.uploader, 'uploader'),
    pipeline: str(r.pipeline, 'pipeline'),
    roles: list(r.roles, 'roles', readRole),
    uploaders: list(r.uploaders, 'uploaders', (u) => str(u, 'an uploader')),
    staleBindings: list(r.staleBindings, 'staleBindings', (s) => ({
      role: str(obj(s, 'a stale binding').role, 'stale binding role'),
      ...readBinding(s, 'a stale binding'),
    })),
  };
}
