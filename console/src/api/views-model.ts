// What the API of the views answers (08-api.md: Pipeline view, ADR-033): the views a version
// declares and one page of the rows of a view. Read here and nowhere else, as model.ts does for the
// rest. The values of the enumerations (a column's type, a tone, a view's status) are kept as they
// came: one a newer Engine adds is shown as it is, not refused. The title, the labels and the cells
// are text from a pipeline and from its database: untrusted (ADR-017).

import { bad, bool, list, num, obj, str, strOrNull, strings, type Obj } from './parse.ts';

export interface ViewParameter {
  name: string;
  /** `TEXT`, `INTEGER` or `CHOICE`. */
  type: string;
  required: boolean;
  /** The fixed values of a `CHOICE`; empty for the others. */
  options: string[];
}

export interface Drill {
  /** The view of the same pipeline that a click opens. */
  view: string;
  /** Its parameter that takes the value. */
  parameter: string;
  /** The column of the row whose value is taken. */
  from: string;
}

export interface ViewColumn {
  name: string;
  /** In the one language the pipeline writes in. */
  label: string;
  /** `TEXT`, `INTEGER`, `TIME` or `STATUS`. */
  type: string;
  /** For a `STATUS` column: how each value is to be read (`POSITIVE`, `NEUTRAL`, `NEGATIVE`). */
  tones: Array<{ value: string; tone: string }>;
  drill: Drill | null;
}

export interface ViewDeclaration {
  id: string;
  title: string;
  description: string;
  /** The resource role the view reads. */
  resource: string;
  /** `ready`, or why the role cannot be used (`unbound`, `unknown`, `disabled`, `type_mismatch`). */
  status: string;
  parameters: ViewParameter[];
  columns: ViewColumn[];
  sort: Array<{ column: string; descending: boolean }>;
}

export interface ViewList {
  contentHash: string;
  /** Whose version this is. */
  uploader: string;
  pipeline: string;
  views: ViewDeclaration[];
}

/** A value of a cell as the database gave it. */
export type Cell = string | number | boolean | null;

export interface ViewPage {
  view: string;
  columns: ViewColumn[];
  rows: Cell[][];
  hasMore: boolean;
  limit: number;
  offset: number;
}

function readColumn(value: unknown): ViewColumn {
  const c: Obj = obj(value, 'a view column');
  return {
    name: str(c.name, 'column name'),
    label: str(c.label, 'column label'),
    type: str(c.type, 'column type'),
    tones: list(c.tones, 'column tones', (t) => {
      const tone = obj(t, 'a tone');
      return { value: str(tone.value, 'tone value'), tone: str(tone.tone, 'tone') };
    }),
    drill:
      c.drill === null || c.drill === undefined
        ? null
        : (() => {
            const d = obj(c.drill, 'a drill');
            return {
              view: str(d.view, 'drill view'),
              parameter: str(d.parameter, 'drill parameter'),
              from: str(d.from, 'drill from'),
            };
          })(),
  };
}

function readView(value: unknown): ViewDeclaration {
  const v: Obj = obj(value, 'a view');
  return {
    id: str(v.id, 'view id'),
    title: str(v.title, 'view title'),
    description: strOrNull(v.description, 'view description') ?? '',
    resource: str(v.resource, 'view resource'),
    status: str(v.status, 'view status'),
    parameters: list(v.parameters, 'view parameters', (p) => {
      const parameter = obj(p, 'a view parameter');
      return {
        name: str(parameter.name, 'parameter name'),
        type: str(parameter.type, 'parameter type'),
        required: bool(parameter.required, 'parameter required'),
        options: strings(parameter.options, 'parameter options'),
      };
    }),
    columns: list(v.columns, 'view columns', readColumn),
    sort: list(v.sort, 'view sort', (s) => {
      const sort = obj(s, 'a sort');
      return { column: str(sort.column, 'sort column'), descending: bool(sort.descending, 'sort descending') };
    }),
  };
}

export function parseViewList(json: unknown): ViewList {
  const r = obj(json, 'the views');
  return {
    contentHash: str(r.contentHash, 'contentHash'),
    uploader: str(r.uploader, 'uploader'),
    pipeline: str(r.pipeline, 'pipeline'),
    views: list(r.views, 'views', readView),
  };
}

const cell = (value: unknown): Cell => {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return value;
  return num(value, 'a cell');
};

export function parseViewPage(json: unknown): ViewPage {
  const r = obj(json, 'a page of a view');
  const columns = list(r.columns, 'page columns', readColumn);
  return {
    view: str(r.view, 'view'),
    columns,
    rows: list(r.rows, 'page rows', (row) => {
      if (!Array.isArray(row) || row.length !== columns.length) {
        throw bad('a row of a view does not have a value for each column');
      }
      return row.map(cell);
    }),
    hasMore: bool(r.hasMore, 'hasMore'),
    limit: num(r.limit, 'limit'),
    offset: num(r.offset, 'offset'),
  };
}
