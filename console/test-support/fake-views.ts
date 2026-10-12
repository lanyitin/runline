// The views of the Fake Engine (08-api.md: Pipeline view, ADR-033): the list of what a version
// declares, with whether the role each is mounted on can be used, and a page of rows. The Fake runs
// no query. What the real Engine reads from the database of the resource, the Fake reads from a
// table it keeps for the resource (`seed`), and a parameter filters the rows on the column of its
// own name; the declaration says the table (FakeView.table). A database the Fake has no table for
// is a database that no run has made, which is `view_schema_mismatch` as on the Engine. Everything
// else (who may see a version, the checks of the parameters and of the page, the order of the rows,
// `hasMore`, the refusals and their codes) is the Engine's, and contract/views-contract.ts holds
// the Fake to it. A test can make a read fail the way a database does (`failNext`).

import {
  answer,
  ambiguousVersion,
  failure,
  type ApiAnswer,
  type ApiRequest,
  type FakeCallerRef,
  type FakeRoute,
} from './fake-api';
import type { Definition, FakeBackend } from './fake-backend';
import type { FakeView } from './fake-jars';

export type Row = Record<string, unknown>;

/** What goes wrong with a read in the database, rather than in the request. */
export type ViewFailure =
  | 'timeout'
  | 'busy'
  | 'connection_failed'
  | 'schema_mismatch'
  | 'query_rejected'
  | 'too_large'
  | 'internal';

/** A view as the metadata of a version keeps it: all that the declaration says, with its query. */
export interface DeclaredView {
  id: string;
  title: string;
  description: string;
  resource: string;
  query: string;
  /** Only the Fake: the table of the resource's database that the rows are read from. */
  table: string;
  parameters: Array<{ name: string; type: string; required: boolean; options: string[] }>;
  columns: Array<{
    name: string;
    label: string;
    type: string;
    tones: Array<{ value: string; tone: string }>;
    drill: { view: string; parameter: string; from: string } | null;
  }>;
  sort: Array<{ column: string; descending: boolean }>;
}

export const declareView = (view: FakeView): DeclaredView => ({
  id: view.id,
  title: view.title,
  description: view.description ?? '',
  resource: view.resource,
  query: `SELECT * FROM ${view.table}`,
  table: view.table,
  parameters: (view.parameters ?? []).map((p) => ({
    name: p.name,
    type: p.type,
    required: p.required ?? false,
    options: p.options ?? [],
  })),
  columns: view.columns.map((c) => ({
    name: c.name,
    label: c.label,
    type: c.type ?? 'TEXT',
    tones: c.tones ?? [],
    drill: c.drill ?? null,
  })),
  sort: (view.sort ?? []).map((s) => ({ column: s.column, descending: s.descending ?? false })),
});

const DEFINITION_NOT_FOUND = () =>
  failure(404, 'definition_not_found', 'No such version and pipeline.');

const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 200;

/** What `demo-views` (samples.views.ViewsPipeline) writes into its database when it runs. */
export const DEMO_VIEWS_DATA: Record<string, Row[]> = {
  feed: [
    { name: 'news', items: 3 },
    { name: 'blog', items: 3 },
  ],
  article: [
    { id: 1, title: 'Alpha launch', state: 'kept', fetched_at: '2026-10-01T08:00:00Z', feed: 'news' },
    {
      id: 2,
      title: 'Beta <b onclick="alert(1)">bold</b> & <script>alert(1)</script>',
      state: 'failed',
      fetched_at: '2026-10-02T08:00:00Z',
      feed: 'news',
    },
    { id: 3, title: 'Gamma', state: 'kept', fetched_at: '2026-10-03T08:00:00Z', feed: 'blog' },
    { id: 4, title: 'Delta', state: 'skipped', fetched_at: '2026-10-04T08:00:00Z', feed: 'blog' },
    { id: 5, title: 'Epsilon', state: 'kept', fetched_at: '2026-10-05T08:00:00Z', feed: 'news' },
    { id: 6, title: 'Zeta', state: 'failed', fetched_at: '2026-10-06T08:00:00Z', feed: 'blog' },
    { id: 7, title: 'Eta', state: 'kept', fetched_at: '2026-10-07T08:00:00Z', feed: null },
  ],
};

export class FakeViews {
  /** The tables of the databases, by the name of the resource and then of the table. */
  private readonly databases = new Map<string, Map<string, Row[]>>();
  private readonly failures = new Map<string, ViewFailure[]>();

  constructor(private readonly backend: FakeBackend) {}

  // ---- what a test arranges ----------------------------------------------------------------

  /** The rows of [table] in the database of [resource], as a pipeline's run would have left them. */
  seed(resource: string, table: string, rows: Row[]) {
    const tables = this.databases.get(resource) ?? new Map<string, Row[]>();
    tables.set(table, rows);
    this.databases.set(resource, tables);
  }

  /** What a run of `demo-views` leaves in the database of [resource]. */
  seedDemo(resource: string) {
    for (const [table, rows] of Object.entries(DEMO_VIEWS_DATA)) this.seed(resource, table, rows);
  }

  /** The next read of a view on [resource] fails as [kind] says; each call is for one read. */
  failNext(resource: string, kind: ViewFailure) {
    this.failures.set(resource, [...(this.failures.get(resource) ?? []), kind]);
  }

  // ---- the calls ---------------------------------------------------------------------------

  readonly routes: FakeRoute[] = [
    {
      method: 'GET',
      pattern: /^\/api\/v1\/definitions\/([^/]+)\/([^/]+)\/views$/,
      admin: false,
      handle: (r, m) => this.list(r, decodeURIComponent(m[1]), decodeURIComponent(m[2])),
    },
    {
      method: 'GET',
      pattern: /^\/api\/v1\/definitions\/([^/]+)\/([^/]+)\/views\/([^/]+)$/,
      admin: false,
      handle: (r, m) =>
        this.read(r, decodeURIComponent(m[1]), decodeURIComponent(m[2]), decodeURIComponent(m[3])),
    },
  ];

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

  /** `ready`, or what is wrong with the role the view is mounted on. */
  private statusOf(uploader: string, definition: Definition, view: DeclaredView): string {
    const role = this.backend.bindings.rolesOf(uploader, definition).find((r) => r.role === view.resource);
    return role?.problem ?? 'ready';
  }

  private list(request: ApiRequest, contentHash: string, pipeline: string): ApiAnswer {
    const target = this.target(request, contentHash, pipeline);
    if ('refusal' in target) return target.refusal;
    return answer(200, {
      contentHash,
      uploader: target.uploader,
      pipeline,
      views: target.definition.metadata.views.map((view) => ({
        id: view.id,
        title: view.title,
        description: view.description,
        resource: view.resource,
        status: this.statusOf(target.uploader, target.definition, view),
        parameters: view.parameters,
        columns: view.columns,
        sort: view.sort,
      })),
    });
  }

  private read(request: ApiRequest, contentHash: string, pipeline: string, viewId: string): ApiAnswer {
    const target = this.target(request, contentHash, pipeline);
    if ('refusal' in target) return target.refusal;
    const view = target.definition.metadata.views.find((v) => v.id === viewId);
    if (!view) return failure(404, 'view_not_found', 'The pipeline declares no such view.');

    const values = this.parameters(request.query, view);
    if ('refusal' in values) return values.refusal;
    const page = pageOf(request.query);
    if ('refusal' in page) return page.refusal;

    const status = this.statusOf(target.uploader, target.definition, view);
    const role = this.backend.bindings
      .rolesOf(target.uploader, target.definition)
      .find((r) => r.role === view.resource)!;
    if (status !== 'ready') {
      return failure(409, 'view_resource_unavailable', 'The role of the view cannot be used.', {
        problem: status,
        role: view.resource,
      });
    }

    const failed = this.failures.get(role.resource)?.shift();
    if (failed) return failedRead(failed);
    const table = this.databases.get(role.resource)?.get(view.table);
    if (!table) {
      return failure(409, 'view_schema_mismatch', 'The database has not the tables of the query.');
    }

    const matching = table.filter((row) =>
      view.parameters.every((p) => {
        const wanted = values.values.get(p.name);
        return wanted === undefined || String(row[p.name]) === wanted;
      }),
    );
    const sorted = [...matching].sort(compareBy(view));
    const rows = sorted
      .slice(page.offset, page.offset + page.limit + 1)
      .map((row) => view.columns.map((c) => row[c.name] ?? null));
    return answer(
      200,
      {
        view: view.id,
        columns: view.columns,
        rows: rows.slice(0, page.limit),
        hasMore: rows.length > page.limit,
        limit: page.limit,
        offset: page.offset,
      },
      { 'Cache-Control': 'no-store' },
    );
  }

  /** The values the request gives, by parameter name (those not given are left out), or the refusal. */
  private parameters(
    query: URLSearchParams,
    view: DeclaredView,
  ): { values: Map<string, string> } | { refusal: ApiAnswer } {
    const refuse = (parameter: string, reason: string) => ({
      refusal: failure(400, 'invalid_view_parameter', 'A parameter is not valid.', { parameter, reason }),
    });
    const declared = new Set(view.parameters.map((p) => p.name));
    for (const key of query.keys()) {
      if (key.startsWith('param.') && !declared.has(key.slice('param.'.length))) {
        return refuse(key.slice('param.'.length), 'unknown');
      }
    }
    const values = new Map<string, string>();
    for (const parameter of view.parameters) {
      const given = query.getAll(`param.${parameter.name}`);
      if (given.length > 1) return refuse(parameter.name, 'repeated');
      const text = given[0];
      if (text === undefined || text === '') {
        if (parameter.required) return refuse(parameter.name, 'missing');
        continue;
      }
      if (parameter.type === 'INTEGER' && !/^[+-]?\d+$/.test(text)) return refuse(parameter.name, 'invalid_type');
      if (parameter.type === 'CHOICE' && !parameter.options.includes(text)) {
        return refuse(parameter.name, 'not_an_option');
      }
      values.set(parameter.name, text);
    }
    return { values };
  }
}

function pageOf(query: URLSearchParams): { limit: number; offset: number } | { refusal: ApiAnswer } {
  const refuse = (parameter: string, reason: string) => ({
    refusal: failure(400, 'invalid_view_parameter', 'A parameter is not valid.', { parameter, reason }),
  });
  const limitText = query.get('limit');
  const offsetText = query.get('offset');
  if (limitText !== null && !/^[+-]?\d+$/.test(limitText)) return refuse('limit', 'invalid_type');
  const limit = limitText === null ? DEFAULT_LIMIT : Number(limitText);
  if (limit < 1 || limit > MAX_LIMIT) return refuse('limit', 'out_of_range');
  if (offsetText !== null && !/^[+-]?\d+$/.test(offsetText)) return refuse('offset', 'invalid_type');
  const offset = offsetText === null ? 0 : Number(offsetText);
  if (offset < 0) return refuse('offset', 'out_of_range');
  return { limit, offset };
}

/** The declared order, then every declared column in the order of the declaration: a total order. */
function compareBy(view: DeclaredView) {
  const order = [
    ...view.sort,
    ...view.columns.map((c) => ({ column: c.name, descending: false })),
  ];
  return (a: Row, b: Row) => {
    for (const { column, descending } of order) {
      const compared = compareValues(a[column] ?? null, b[column] ?? null);
      if (compared !== 0) return descending ? -compared : compared;
    }
    return 0;
  };
}

function compareValues(a: unknown, b: unknown): number {
  if (a === b) return 0;
  if (a === null) return -1; // as SQLite: nothing is smaller than anything
  if (b === null) return 1;
  if (typeof a === 'number' && typeof b === 'number') return a < b ? -1 : 1;
  return String(a) < String(b) ? -1 : 1;
}

function failedRead(kind: ViewFailure): ApiAnswer {
  switch (kind) {
    case 'timeout':
      return failure(504, 'view_timeout', 'The database did not answer in time.');
    case 'busy':
      return failure(429, 'view_busy', 'Too many reads of this resource are going on.');
    case 'connection_failed':
      return failure(502, 'view_connection_failed', 'The database cannot be reached.');
    case 'schema_mismatch':
      return failure(409, 'view_schema_mismatch', 'The database has not the tables of the query.');
    case 'query_rejected':
      return failure(422, 'view_query_rejected', 'The database does not accept the query.');
    case 'too_large':
      return failure(422, 'view_result_too_large', 'The page is larger than the resource allows.');
    case 'internal':
      return failure(500, 'internal_error', 'The Engine failed unexpectedly.', {
        errorId: '0b1c2d3e-4f50-4a6b-8c7d-9e0f1a2b3c4d',
      });
  }
}

/** A developer may use the versions they uploaded; an admin, any. */
const mayUse = (caller: FakeCallerRef, uploader: string) =>
  caller.role === 'admin' || caller.name === uploader;
