// The allow-list of the Fake Engine (08-api.md: Allow-list): its entries, its versions and what a
// change does to the verdicts. The Fake does not analyse a class file: each pipeline of a jar of the
// Fake says which classes it refers to (`references`), and a reference is a reason to call the
// pipeline UNSAFE when no entry covers the class. That is the whole of the analysis, and enough for
// what the screens show: which definitions become UNSAFE or SAFE, and what is taken back. The
// contract tests (contract/admin-contract.ts) hold it to the Engine.

import {
  answer,
  failure,
  jsonObject,
  type ApiAnswer,
  type ApiRequest,
  type FakeRoute,
} from './fake-api';
import type { FakeBackend, Definition } from './fake-backend';

export interface FakeEntry {
  kind: 'package' | 'class';
  name: string;
  /** Only a package entry has it. */
  exactOnly: boolean | null;
  createdBy: string;
  createdAt: string;
  updatedBy: string;
  updatedAt: string;
}

export interface FakeAllowListVersion {
  version: number;
  changedBy: string;
  changedAt: string;
  action: 'INITIAL' | 'ENTRY_ADDED' | 'ENTRY_CHANGED' | 'ENTRY_REMOVED';
  detail: string;
  rejudgedDefinitions: number;
  becameUnsafe: number;
  becameSafe: number;
}

/** The default allow-list of the Engine (`default-2`): `[kind, name, exactOnly]`. */
const DEFAULT_ENTRIES: ReadonlyArray<readonly [FakeEntry['kind'], string, boolean | null]> = [
  ['package', 'java.lang', true],
  ['package', 'java.lang.invoke', false],
  ['package', 'java.lang.annotation', false],
  ['package', 'java.lang.ref', false],
  ['package', 'java.lang.runtime', false],
  ['package', 'java.lang.constant', false],
  ['package', 'java.util', true],
  ['package', 'java.util.concurrent', false],
  ['package', 'java.util.function', false],
  ['package', 'java.util.regex', false],
  ['package', 'java.util.stream', false],
  ['package', 'java.util.random', false],
  ['package', 'java.time', false],
  ['package', 'java.math', false],
  ['package', 'java.text', false],
  ['package', 'java.nio.charset', false],
  ['package', 'kotlin', true],
  ['package', 'kotlin.annotation', false],
  ['package', 'kotlin.collections', false],
  ['package', 'kotlin.comparisons', false],
  ['package', 'kotlin.concurrent', false],
  ['package', 'kotlin.contracts', false],
  ['package', 'kotlin.coroutines', false],
  ['package', 'kotlin.enums', false],
  ['package', 'kotlin.experimental', false],
  ['package', 'kotlin.internal', false],
  ['package', 'kotlin.jdk7', false],
  ['package', 'kotlin.jvm', false],
  ['package', 'kotlin.math', false],
  ['package', 'kotlin.properties', false],
  ['package', 'kotlin.random', false],
  ['package', 'kotlin.ranges', false],
  ['package', 'kotlin.reflect', false],
  ['package', 'kotlin.sequences', false],
  ['package', 'kotlin.streams', false],
  ['package', 'kotlin.system', false],
  ['package', 'kotlin.text', false],
  ['package', 'kotlin.time', false],
  ['package', 'kotlin.uuid', false],
  ['package', 'org.jetbrains.annotations', false],
  ['class', 'java.io.PrintStream', null],
  ['class', 'kotlin.io.ConsoleKt', null],
  ['class', 'kotlin.io.CloseableKt', null],
  ['class', 'java.io.Closeable', null],
];

const IDENTIFIER = /^[A-Za-z_$][A-Za-z0-9_$]*$/;
const validName = (kind: FakeEntry['kind'], name: string) => {
  const parts = name.split('.');
  return parts.every((p) => IDENTIFIER.test(p)) && (kind === 'package' || parts.length > 1);
};

const packageOf = (className: string) => className.slice(0, Math.max(0, className.lastIndexOf('.')));

/** Whether [entry] lets [className] through (a class, or what is in a nested class of it). */
function covers(entry: Pick<FakeEntry, 'kind' | 'name' | 'exactOnly'>, className: string): boolean {
  if (entry.kind === 'class') {
    return className === entry.name || className.startsWith(`${entry.name}$`);
  }
  const pkg = packageOf(className);
  return entry.exactOnly ? pkg === entry.name : pkg === entry.name || pkg.startsWith(`${entry.name}.`);
}

/** Whether [existing] makes [candidate] (an entry that is not there yet) unnecessary. */
function entryCovers(
  existing: Pick<FakeEntry, 'kind' | 'name' | 'exactOnly'>,
  candidate: Pick<FakeEntry, 'kind' | 'name' | 'exactOnly'>,
): boolean {
  if (candidate.kind === 'class') return covers(existing, candidate.name);
  if (existing.kind === 'class' || existing.exactOnly) return false;
  return candidate.name.startsWith(`${existing.name}.`);
}

const sameEntry = (a: Pick<FakeEntry, 'kind' | 'name'>, b: Pick<FakeEntry, 'kind' | 'name'>) =>
  a.kind === b.kind && a.name === b.name;

/** The verdict and the reasons of [definition] under [entries]. */
export function judge(definition: Definition, entries: readonly FakeEntry[]) {
  const reasons = [
    ...definition.fixedReasons,
    ...definition.references
      .filter((reference) => !entries.some((entry) => covers(entry, reference.className)))
      .map((reference) => ({
        kind: 'NOT_ALLOW_LISTED',
        category: null,
        className: reference.className,
        member: null,
        path: reference.path,
        detail: null,
      })),
  ];
  return { verdict: reasons.length > 0 ? ('UNSAFE' as const) : ('SAFE' as const), reasons };
}

type Operation =
  | { kind: 'add'; entry: { kind: FakeEntry['kind']; name: string; exactOnly: boolean } }
  | { kind: 'modify'; key: { kind: FakeEntry['kind']; name: string }; name?: string; exactOnly?: boolean }
  | { kind: 'remove'; key: { kind: FakeEntry['kind']; name: string } }
  | { kind: 'recheck' };

export class FakeAllowList {
  entries: FakeEntry[];
  readonly versions: FakeAllowListVersion[];

  constructor(private readonly backend: FakeBackend) {
    const at = new Date().toISOString();
    this.entries = DEFAULT_ENTRIES.map(([kind, name, exactOnly]) => ({
      kind,
      name,
      exactOnly,
      createdBy: 'system',
      createdAt: at,
      updatedBy: 'system',
      updatedAt: at,
    }));
    this.versions = [
      {
        version: 1,
        changedBy: 'system',
        changedAt: at,
        action: 'INITIAL',
        detail: `Initial content from the default allow list default-2 (${this.entries.length} entries)`,
        rejudgedDefinitions: 0,
        becameUnsafe: 0,
        becameSafe: 0,
      },
    ];
  }

  get current(): FakeAllowListVersion {
    return this.versions[this.versions.length - 1];
  }

  readonly routes: FakeRoute[] = [
    { method: 'GET', pattern: /^\/api\/v1\/allowlist$/, admin: true, handle: () => this.read() },
    {
      method: 'GET',
      pattern: /^\/api\/v1\/allowlist\/versions$/,
      admin: true,
      handle: (r) => this.history(r),
    },
    {
      method: 'POST',
      pattern: /^\/api\/v1\/allowlist\/entries$/,
      admin: true,
      handle: (r) => this.add(r),
    },
    {
      method: 'GET',
      pattern: /^\/api\/v1\/allowlist\/entries\/([^/]+)\/([^/]+)$/,
      admin: true,
      handle: (_r, m) => this.readEntry(m[1], m[2]),
    },
    {
      method: 'PATCH',
      pattern: /^\/api\/v1\/allowlist\/entries\/([^/]+)\/([^/]+)$/,
      admin: true,
      handle: (r, m) => this.modify(r, m[1], m[2]),
    },
    {
      method: 'DELETE',
      pattern: /^\/api\/v1\/allowlist\/entries\/([^/]+)\/([^/]+)$/,
      admin: true,
      handle: (r, m) => this.remove(r, m[1], m[2]),
    },
    {
      method: 'POST',
      pattern: /^\/api\/v1\/allowlist\/recheck$/,
      admin: true,
      handle: (r) => this.change(r, { kind: 'recheck' }),
    },
  ];

  private limitations = () =>
    'The analysis only checks the class references of the compiled classes (a Fake).';

  private read(): ApiAnswer {
    const { version, changedBy, changedAt } = this.current;
    return answer(200, {
      version: String(version),
      changedBy,
      changedAt,
      entries: this.entries,
      limitations: this.limitations(),
    });
  }

  private history({ query }: ApiRequest): ApiAnswer {
    const asked = Number(query.get('limit'));
    const limit = Number.isInteger(asked) && asked > 0 ? Math.min(asked, 200) : 50;
    const versions = [...this.versions]
      .reverse()
      .slice(0, limit)
      .map((v) => ({ ...v, version: String(v.version) }));
    return answer(200, { versions });
  }

  private kindOf(text: string): FakeEntry['kind'] | null {
    return text === 'package' || text === 'class' ? text : null;
  }

  private notFound = () => failure(404, 'entry_not_found', 'No such allow-list entry.');
  private badKind = () => failure(400, 'bad_request', 'kind must be package or class.');

  private readEntry(kindText: string, name: string): ApiAnswer {
    const kind = this.kindOf(kindText);
    if (!kind) return this.badKind();
    const entry = this.entries.find((e) => sameEntry(e, { kind, name: decodeURIComponent(name) }));
    return entry ? answer(200, entry) : this.notFound();
  }

  // ---- changes ---------------------------------------------------------------------------------

  private add(request: ApiRequest): ApiAnswer {
    const body = jsonObject(request.body);
    if (
      !body ||
      typeof body.name !== 'string' ||
      (body.exactOnly !== undefined && typeof body.exactOnly !== 'boolean')
    ) {
      return failure(400, 'bad_request', 'The body needs kind, name and, for a package, exactOnly.');
    }
    const kind = typeof body.kind === 'string' ? this.kindOf(body.kind) : null;
    if (!kind) return this.badKind();
    return this.change(request, {
      kind: 'add',
      entry: { kind, name: body.name, exactOnly: body.exactOnly === true },
    });
  }

  private modify(request: ApiRequest, kindText: string, name: string): ApiAnswer {
    const kind = this.kindOf(kindText);
    if (!kind) return this.badKind();
    const body = jsonObject(request.body);
    if (
      !body ||
      (body.name !== undefined && typeof body.name !== 'string') ||
      (body.exactOnly !== undefined && typeof body.exactOnly !== 'boolean')
    ) {
      return failure(400, 'bad_request', 'The body may have name and exactOnly.');
    }
    return this.change(request, {
      kind: 'modify',
      key: { kind, name: decodeURIComponent(name) },
      name: body.name as string | undefined,
      exactOnly: body.exactOnly as boolean | undefined,
    });
  }

  private remove(request: ApiRequest, kindText: string, name: string): ApiAnswer {
    const kind = this.kindOf(kindText);
    if (!kind) return this.badKind();
    return this.change(request, { kind: 'remove', key: { kind, name: decodeURIComponent(name) } });
  }

  private invalidEntry(problem: string, message: string) {
    return failure(422, 'invalid_entry', message, { problem });
  }

  /** The entries after [operation], with the entry it made, or the answer that refuses it. */
  private apply(
    operation: Operation,
    by: string,
    at: string,
  ): { entries: FakeEntry[]; entry: FakeEntry | null; action: FakeAllowListVersion['action'] | null; detail: string } | ApiAnswer {
    const entries = this.entries.map((e) => ({ ...e }));
    if (operation.kind === 'recheck') {
      return { entries, entry: null, action: null, detail: 'Recheck' };
    }
    if (operation.kind === 'add') {
      const { kind, name, exactOnly } = operation.entry;
      if (kind === 'class' && exactOnly) {
        return this.invalidEntry('exact_only_on_class', 'exactOnly applies to packages only.');
      }
      if (!validName(kind, name)) return this.invalidEntry('name', 'The name is not valid.');
      const existing = entries.find((e) => sameEntry(e, { kind, name }));
      if (existing) {
        return failure(409, 'entry_exists', 'The entry exists; use PATCH.', { existing });
      }
      const candidate = { kind, name, exactOnly: kind === 'package' ? exactOnly : null };
      const coveredBy = entries.find((e) => entryCovers(e, candidate));
      if (coveredBy) {
        return failure(409, 'entry_covered', 'Another entry covers it.', { coveredBy });
      }
      const entry: FakeEntry = {
        ...candidate,
        createdBy: by,
        createdAt: at,
        updatedBy: by,
        updatedAt: at,
      };
      entries.push(entry);
      return { entries, entry, action: 'ENTRY_ADDED', detail: `Added ${kind} ${name}` };
    }

    const position = entries.findIndex((e) => sameEntry(e, operation.key));
    if (position < 0) return this.notFound();
    if (operation.kind === 'remove') {
      entries.splice(position, 1);
      return {
        entries,
        entry: null,
        action: 'ENTRY_REMOVED',
        detail: `Removed ${operation.key.kind} ${operation.key.name}`,
      };
    }
    // modify
    const target = entries[position];
    if (operation.name === undefined && operation.exactOnly === undefined) {
      return this.invalidEntry('nothing_to_change', 'There is nothing to change.');
    }
    if (operation.exactOnly !== undefined && target.kind === 'class') {
      return this.invalidEntry('exact_only_on_class', 'exactOnly applies to packages only.');
    }
    const name = operation.name ?? target.name;
    if (!validName(target.kind, name)) return this.invalidEntry('name', 'The name is not valid.');
    if (name !== target.name && entries.some((e) => sameEntry(e, { kind: target.kind, name }))) {
      const existing = entries.find((e) => sameEntry(e, { kind: target.kind, name }));
      return failure(409, 'entry_exists', 'The entry exists.', { existing });
    }
    const changed: FakeEntry = {
      ...target,
      name,
      exactOnly: target.kind === 'package' ? (operation.exactOnly ?? target.exactOnly) : null,
      updatedBy: by,
      updatedAt: at,
    };
    const others = entries.filter((_, i) => i !== position);
    const coveredBy = others.find((e) => entryCovers(e, changed));
    if (coveredBy) return failure(409, 'entry_covered', 'Another entry covers it.', { coveredBy });
    entries[position] = changed;
    return {
      entries,
      entry: changed,
      action: 'ENTRY_CHANGED',
      detail: `Changed ${target.kind} ${operation.key.name}`,
    };
  }

  private change(request: ApiRequest, operation: Operation): ApiAnswer {
    const previewText = request.query.get('preview');
    if (previewText !== null && previewText !== 'true' && previewText !== 'false') {
      return failure(400, 'bad_request', 'preview must be true or false.');
    }
    const preview = previewText === 'true';
    const by = request.caller.name;
    const at = new Date().toISOString();
    const made = this.apply(operation, by, at);
    if ('status' in made) return made;

    const made_ = made.entry;
    const redundant = made_ ? made.entries.filter((e) => e !== made_ && entryCovers(made_, e)) : [];

    const changes: Array<Record<string, unknown>> = [];
    let examinedDefinitions = 0;
    const judged: Array<{ definition: Definition; now: ReturnType<typeof judge>; revoke: boolean }> = [];
    for (const { contentHash, definition } of this.backend.allDefinitions()) {
      examinedDefinitions += 1;
      const now = judge(definition, made.entries);
      const changed = now.verdict !== definition.verdict;
      const revoke = changed && now.verdict === 'UNSAFE' && definition.allowUnsafeExecution;
      judged.push({ definition, now, revoke });
      if (changed) {
        changes.push({
          contentHash,
          pipeline: definition.name,
          className: definition.className,
          from: definition.verdict,
          to: now.verdict,
          allowUnsafeExecution: definition.allowUnsafeExecution,
          unsafeExecutionRevoked: revoke,
        });
      }
    }
    const becameUnsafe = changes.filter((c) => c.to === 'UNSAFE').length;
    const becameSafe = changes.filter((c) => c.to === 'SAFE').length;
    const impact = {
      examinedArtifacts: this.backend.artifacts.size,
      examinedDefinitions,
      becameUnsafe,
      becameSafe,
      unreadable: 0,
      changes,
    };

    if (preview) {
      return answer(200, {
        preview: true,
        version: String(this.current.version),
        entry: null,
        impact,
        redundantEntries: redundant,
        limitations: this.limitations(),
      });
    }

    const version = made.action === null ? this.current.version : this.current.version + 1;
    if (made.action !== null) {
      this.entries = made.entries;
      this.versions.push({
        version,
        changedBy: by,
        changedAt: at,
        action: made.action,
        detail: made.detail,
        rejudgedDefinitions: examinedDefinitions,
        becameUnsafe,
        becameSafe,
      });
    }
    for (const { definition, now, revoke } of judged) {
      definition.verdict = now.verdict;
      definition.reasons = now.reasons;
      if (made.action !== null) definition.allowListVersion = String(version);
      if (revoke) {
        definition.allowUnsafeExecution = false;
        definition.unsafeSetBy = by;
        definition.unsafeSetAt = at;
      }
    }
    const body = {
      preview: false,
      version: String(version),
      entry: made.entry,
      impact,
      redundantEntries: redundant,
      limitations: this.limitations(),
    };
    return operation.kind === 'add'
      ? answer(201, body, {
          Location: `/api/v1/allowlist/entries/${operation.entry.kind}/${encodeURIComponent(operation.entry.name)}`,
        })
      : answer(200, body);
  }
}
