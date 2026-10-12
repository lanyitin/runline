// What the data section of a pipeline's page keeps in the address, and the small decisions it makes
// from what the Engine told. The address is where the state lives (the view, the values of its
// parameters, the page), so that a reload, the browser's back button and a link all bring a person
// to the same table, and so that coming back from a drill finds the view as it was left. Nothing
// here is a credential (ADR-017); the values of the parameters are what the person typed.

import type { Cell, Drill, ViewColumn, ViewDeclaration } from '../api/views-model';
import { pipelineHref } from '../app/links';

const PARAM_PREFIX = 'param.';

export interface ViewState {
  /** The id of the view chosen; null when none is. */
  view: string | null;
  /** The values of its parameters by name. */
  parameters: Record<string, string>;
  /** Rows skipped before the page. */
  offset: number;
}

export function readViewState(search: string): ViewState {
  const query = new URLSearchParams(search);
  const parameters: Record<string, string> = {};
  for (const [key, value] of query) {
    if (key.startsWith(PARAM_PREFIX)) parameters[key.slice(PARAM_PREFIX.length)] = value;
  }
  const offset = query.get('offset') ?? '';
  return {
    view: query.get('view'),
    parameters,
    offset: /^\d+$/.test(offset) ? Number(offset) : 0,
  };
}

/** The address of the pipeline's page with [state] of the data section in it. */
export function viewHref(contentHash: string, pipeline: string, uploader: string, state: ViewState): string {
  const query = new URLSearchParams();
  if (state.view !== null) {
    query.set('view', state.view);
    for (const [name, value] of Object.entries(state.parameters)) {
      if (value !== '') query.set(`${PARAM_PREFIX}${name}`, value);
    }
    if (state.offset > 0) query.set('offset', String(state.offset));
  }
  const extra = query.toString();
  return `${pipelineHref(contentHash, pipeline, uploader)}${extra ? `&${extra}` : ''}`;
}

export interface ParameterProblem {
  name: string;
  /** As the Engine says it: `missing`, `invalid_type`, `not_an_option`. */
  reason: 'missing' | 'invalid_type' | 'not_an_option';
}

/** What is wrong with [values] for [view], as the Engine would refuse it; in the order declared. */
export function invalidParameters(view: ViewDeclaration, values: Record<string, string>): ParameterProblem[] {
  const problems: ParameterProblem[] = [];
  for (const parameter of view.parameters) {
    const value = values[parameter.name] ?? '';
    if (value === '') {
      if (parameter.required) problems.push({ name: parameter.name, reason: 'missing' });
    } else if (parameter.type === 'INTEGER' && !/^[+-]?\d+$/.test(value)) {
      problems.push({ name: parameter.name, reason: 'invalid_type' });
    } else if (parameter.type === 'CHOICE' && !parameter.options.includes(value)) {
      problems.push({ name: parameter.name, reason: 'not_an_option' });
    }
  }
  return problems;
}

/**
 * The state a click on a cell of [column] opens: the view of the drill with the value of the
 * column it takes from as its parameter. None when the column declares no drill, or when the row
 * has nothing there (a null is no value to ask about).
 */
export function drillState(column: ViewColumn, columns: ViewColumn[], row: Cell[]): ViewState | null {
  const drill: Drill | null = column.drill;
  if (drill === null) return null;
  const value = row[columns.findIndex((c) => c.name === drill.from)];
  if (value === null || value === undefined) return null;
  return { view: drill.view, parameters: { [drill.parameter]: String(value) }, offset: 0 };
}

const INSTANT = /^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}(?::\d{2})?)(\.\d+)?(Z|[+-]\d{2}(?::?\d{2})?)$/;

/**
 * The instant a cell of a time column says, when it says it as ISO-8601 with its offset (a `T` or a
 * space between the date and the time). A time without an offset is not guessed at: it is shown as
 * the database gave it.
 */
export function instantOf(value: Cell): Date | null {
  if (typeof value !== 'string') return null;
  const found = INSTANT.exec(value);
  if (!found) return null;
  const [, date, time, fraction, zone] = found;
  const seconds = time.length === 5 ? `${time}:00` : time;
  const millis = fraction ? `.${fraction.slice(1, 4).padEnd(3, '0')}` : '';
  const offset =
    zone === 'Z' ? 'Z' : zone.length === 3 ? `${zone}:00` : zone.includes(':') ? zone : `${zone.slice(0, 3)}:${zone.slice(3)}`;
  const instant = new Date(`${date}T${seconds}${millis}${offset}`);
  return Number.isNaN(instant.getTime()) ? null : instant;
}
