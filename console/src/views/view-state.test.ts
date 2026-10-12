import { describe, expect, test } from 'vitest';
import type { ViewColumn, ViewDeclaration } from '../api/views-model';
import {
  drillState,
  instantOf,
  invalidParameters,
  readViewState,
  viewHref,
  type ViewState,
} from './view-state';

// What the address of the pipeline's page says about the data section (the view chosen, the values
// of its parameters, the page), and the small decisions the section makes from what the Engine told.

const view = (parameters: ViewDeclaration['parameters'], columns: ViewColumn[] = []): ViewDeclaration => ({
  id: 'articles',
  title: 'Articles',
  description: '',
  resource: 'db',
  status: 'ready',
  parameters,
  columns,
  sort: [],
});
const param = (name: string, type = 'TEXT', required = false, options: string[] = []) => ({
  name,
  type,
  required,
  options,
});

describe('the state of the data section in the address', () => {
  test('is nothing chosen when the address has no view', () => {
    expect(readViewState('?pipeline=feed&uploader=ada')).toEqual({ view: null, parameters: {}, offset: 0 });
  });

  test('is the view, the values of its parameters (param.<name>) and the offset', () => {
    expect(readViewState('?pipeline=feed&view=articles&param.feed=news&param.state=kept&offset=100')).toEqual({
      view: 'articles',
      parameters: { feed: 'news', state: 'kept' },
      offset: 100,
    });
  });

  test('takes an offset that is no whole number, or below zero, as the first page', () => {
    for (const bad of ['x', '-5', '1.5', '']) {
      expect(readViewState(`?view=a&offset=${bad}`).offset).toBe(0);
    }
  });

  test('is written back into an address that keeps the pipeline and the uploader, and leaves the offset out for the first page', () => {
    const state: ViewState = { view: 'articles', parameters: { feed: 'a&b=c', state: '' }, offset: 0 };
    const href = viewHref('ab'.repeat(32), 'feed', 'ada', state);
    const url = new URL(href, 'http://x');

    expect(url.pathname).toBe(`/pipelines/${'ab'.repeat(32)}`);
    expect(Object.fromEntries(url.searchParams)).toEqual({
      pipeline: 'feed',
      uploader: 'ada',
      view: 'articles',
      'param.feed': 'a&b=c',
    });
    expect(readViewState(url.search)).toEqual({ view: 'articles', parameters: { feed: 'a&b=c' }, offset: 0 });
  });

  test('keeps the offset of a page after the first', () => {
    const href = viewHref('ab'.repeat(32), 'feed', 'ada', { view: 'articles', parameters: {}, offset: 50 });
    expect(new URL(href, 'http://x').searchParams.get('offset')).toBe('50');
  });
});

describe('the values of the parameters', () => {
  test('are refused when a required one is empty, and the name of each is said', () => {
    const v = view([param('feed', 'TEXT', true), param('state')]);
    expect(invalidParameters(v, { feed: '', state: '' })).toEqual([{ name: 'feed', reason: 'missing' }]);
    expect(invalidParameters(v, { feed: ' ' })).toEqual([]);
  });

  test('are refused when an integer is not a whole number', () => {
    const v = view([param('count', 'INTEGER')]);
    expect(invalidParameters(v, { count: '12' })).toEqual([]);
    expect(invalidParameters(v, { count: '-3' })).toEqual([]);
    expect(invalidParameters(v, { count: '1.5' })).toEqual([{ name: 'count', reason: 'invalid_type' }]);
    expect(invalidParameters(v, { count: 'abc' })).toEqual([{ name: 'count', reason: 'invalid_type' }]);
  });

  test('are refused when a choice is not one of the options', () => {
    const v = view([param('state', 'CHOICE', false, ['kept', 'failed'])]);
    expect(invalidParameters(v, { state: 'kept' })).toEqual([]);
    expect(invalidParameters(v, { state: 'other' })).toEqual([{ name: 'state', reason: 'not_an_option' }]);
  });
});

describe('a drill', () => {
  const columns: ViewColumn[] = [
    { name: 'id', label: 'ID', type: 'INTEGER', tones: [], drill: null },
    {
      name: 'feed',
      label: 'Feed',
      type: 'TEXT',
      tones: [],
      drill: { view: 'articles', parameter: 'feed', from: 'name' },
    },
    { name: 'name', label: 'Name', type: 'TEXT', tones: [], drill: null },
  ];

  test('opens the target view with the value of the column it takes from, as text', () => {
    expect(drillState(columns[1], columns, [7, 'x', 'news'])).toEqual({
      view: 'articles',
      parameters: { feed: 'news' },
      offset: 0,
    });
    const numbers: ViewColumn[] = [
      { ...columns[0], drill: { view: 'detail', parameter: 'id', from: 'id' } },
    ];
    expect(drillState(numbers[0], numbers, [42])?.parameters).toEqual({ id: '42' });
  });

  test('is none for a column that declares none, and for a row with nothing in the column it takes from', () => {
    expect(drillState(columns[0], columns, [7, 'x', 'news'])).toBeNull();
    expect(drillState(columns[1], columns, [7, 'x', null])).toBeNull();
  });
});

describe('a time', () => {
  test('is an instant when the text is ISO-8601 with its offset, with a T or a space between date and time', () => {
    expect(instantOf('2026-10-07T08:00:00Z')?.toISOString()).toBe('2026-10-07T08:00:00.000Z');
    expect(instantOf('2026-10-07T10:00:00+02:00')?.toISOString()).toBe('2026-10-07T08:00:00.000Z');
    expect(instantOf('2026-10-07 08:00:00+00')?.toISOString()).toBe('2026-10-07T08:00:00.000Z');
    expect(instantOf('2026-10-07T08:00:00.123456Z')?.toISOString()).toBe('2026-10-07T08:00:00.123Z');
  });

  test('is none when the zone is not said (the Console does not guess it) or the value is not a time', () => {
    expect(instantOf('2026-10-07 08:00:00')).toBeNull();
    expect(instantOf('yesterday')).toBeNull();
    expect(instantOf(1760000000)).toBeNull();
    expect(instantOf(null)).toBeNull();
  });
});
