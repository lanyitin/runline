import { afterEach, describe, expect, test, vi } from 'vitest';
import { tick } from 'svelte';
import { createTestApp, type TestApp } from '../../test-support/app';
import { viewsPipeline, type FakePipeline } from '../../test-support/fake-jars';
import type { ViewFailure } from '../../test-support/fake-views';
import PipelineDetailPage from './PipelineDetailPage.svelte';

// The data section of a pipeline's page (ADR-033, WI-1302): the views the version declares, the
// inputs of the one chosen, its table with paging and drill, the words for what can go wrong, and
// what a person may see. The Fake Engine behind is held to the real one by the contract tests.

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };
const ada = { name: 'ada', role: 'developer' as const };
const bob = { name: 'bob', role: 'developer' as const };
const HASH = 'c7d1'.padEnd(64, '5');

/** A pipeline of shops: a view with parameters of each kind and a drill, and the view it opens. */
const shop: FakePipeline = {
  name: 'shop',
  className: 'com.acme.Shop',
  resources: ['shop-db'],
  resourceTypes: { 'shop-db': 'jdbc-pool' },
  views: [
    {
      id: 'orders',
      title: 'Orders',
      description: 'Every order, newest last',
      resource: 'shop-db',
      table: 'orders',
      parameters: [
        { name: 'status', type: 'CHOICE', options: ['open', 'paid'] },
        { name: 'total', type: 'INTEGER' },
        { name: 'note', type: 'TEXT' },
      ],
      columns: [
        { name: 'id', label: 'Order', type: 'INTEGER' },
        {
          name: 'customer',
          label: 'Customer',
          drill: { view: 'history', parameter: 'customer', from: 'customer' },
        },
        {
          name: 'status',
          label: 'Status',
          type: 'STATUS',
          tones: [
            { value: 'paid', tone: 'POSITIVE' },
            { value: 'open', tone: 'NEUTRAL' },
            { value: 'refused', tone: 'NEGATIVE' },
          ],
        },
        { name: 'placed', label: 'Placed', type: 'TIME' },
        { name: 'total', label: 'Total', type: 'INTEGER' },
        { name: 'note', label: 'Note' },
      ],
      sort: [{ column: 'id' }],
    },
    {
      id: 'history',
      title: 'History of a customer',
      description: '',
      resource: 'shop-db',
      table: 'orders',
      parameters: [{ name: 'customer', type: 'TEXT', required: true }],
      columns: [
        { name: 'id', label: 'Order', type: 'INTEGER' },
        { name: 'status', label: 'Status', type: 'STATUS' },
      ],
      sort: [{ column: 'id' }],
    },
  ],
};

/** 120 orders: even ones paid, odd ones open; seven customers in turn. */
const orders = Array.from({ length: 120 }, (_, index) => ({
  id: index + 1,
  customer: `c${(index + 1) % 7}`,
  status: (index + 1) % 2 === 0 ? 'paid' : 'open',
  placed: '2026-10-07T08:00:00Z',
  total: (index + 1) * 10,
  note: null,
}));

interface Options {
  identity?: { name: string; role: 'admin' | 'developer' };
  uploaders?: string[];
  pipeline?: FakePipeline;
  languages?: string[];
  /** The query of the pipeline's address, after `pipeline=...&uploader=...`. */
  extra?: string;
  /** The uploader the address names. */
  uploader?: string;
  bind?: boolean;
  seed?: (backend: TestApp['engine']['backend']) => void;
}

const page = async (options: Options = {}) => {
  app = await createTestApp({ identity: options.identity ?? ada, languages: options.languages });
  const backend = app.engine.backend;
  const definition = options.pipeline ?? shop;
  const uploaders = options.uploaders ?? ['ada'];
  for (const who of uploaders) {
    backend.seedArtifact(who, [definition], { contentHash: HASH });
  }
  backend.resources.define('main-db', { type: 'jdbc-pool' });
  if (options.bind ?? true) {
    for (const who of uploaders) backend.bindings.seed(who, definition.name, definition.resources![0], 'main-db');
  }
  backend.views.seed('main-db', 'orders', orders);
  options.seed?.(backend);
  const uploader = options.uploader ?? uploaders[0];
  app.context.router.navigate(
    `/pipelines/x?pipeline=${definition.name}&uploader=${uploader}${options.extra ? `&${options.extra}` : ''}`,
  );
  const view = app.mount(PipelineDetailPage, { contentHash: HASH });
  await vi.waitFor(() => expect(view.querySelector('section.views')).not.toBeNull());
  return view;
};

const section = (view: HTMLElement) => view.querySelector<HTMLElement>('section.views')!;
const text = (element: Element | null) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const press = async (element: ParentNode, selector: string) => {
  const button = element.querySelector<HTMLButtonElement>(selector);
  expect(button, selector).not.toBeNull();
  button!.click();
  await tick();
};
const fill = async (element: ParentNode, name: string, value: string) => {
  const input = element.querySelector<HTMLInputElement | HTMLSelectElement>(`[data-param="${name}"]`)!;
  expect(input, name).not.toBeNull();
  input.value = value;
  input.dispatchEvent(new Event(input instanceof HTMLSelectElement ? 'change' : 'input', { bubbles: true }));
  await tick();
};
const rows = (view: HTMLElement) => [...section(view).querySelectorAll('table tbody tr')];
const column = (view: HTMLElement, index: number) =>
  rows(view).map((row) => text(row.querySelectorAll('td')[index]));
const search = () => new URLSearchParams(app.context.router.search);
const waitForTable = (view: HTMLElement) => vi.waitFor(() => expect(rows(view).length).toBeGreaterThan(0));
const readsOf = () => app.engine.received.filter((r) => /\/views\/[^/?]+/.test(r.path));

describe('the views of a pipeline', () => {
  test('are listed in a section of their own with title, description and whether each can be read', async () => {
    const view = await page();
    await vi.waitFor(() => expect(section(view).querySelectorAll('li.view').length).toBe(2));

    const items = [...section(view).querySelectorAll('li.view')];
    expect(items.map((li) => li.getAttribute('data-view'))).toEqual(['orders', 'history']);
    expect(text(items[0].querySelector('.view-title'))).toBe('Orders');
    expect(text(items[0].querySelector('.view-description'))).toBe('Every order, newest last');
    expect(text(items[0].querySelector('.view-status'))).toBe('Ready');
    expect(items[1].querySelector('.view-description')).toBeNull();
    expect(section(view).querySelector('h2')!.textContent).toBe('Data');
  });

  test('are no section at all for a pipeline that declares none', async () => {
    app = await createTestApp({ identity: ada });
    app.engine.backend.seedArtifact('ada', [{ name: 'bare', className: 'x.Bare' }], { contentHash: HASH });
    app.context.router.navigate('/pipelines/x?pipeline=bare&uploader=ada');
    const view = app.mount(PipelineDetailPage, { contentHash: HASH });
    await vi.waitFor(() => expect(app.engine.received.some((r) => /\/views\b/.test(r.path))).toBe(true));
    await vi.waitFor(() => expect(view.querySelector('section.bindings')).not.toBeNull());
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(view.querySelector('section.views')).toBeNull();
  });

  test('say, for a view whose role cannot be used, why, and are not opened', async () => {
    const view = await page({ bind: false });
    await vi.waitFor(() => expect(section(view).querySelectorAll('li.view').length).toBe(2));

    const first = section(view).querySelector('li.view')!;
    expect(text(first.querySelector('.view-status'))).toBe('Not bound');
    expect(text(first.querySelector('.view-problem'))).toContain('shared resource');
    expect(first.querySelector<HTMLButtonElement>('button.open')!.disabled).toBe(true);
    expect(first.querySelector('.view-problem a')?.getAttribute('href')).toContain('role=shop-db');
  });

  test('say a role that is disabled as that', async () => {
    const view = await page({ seed: (backend) => backend.resources.define('main-db', { type: 'jdbc-pool', enabled: false }) });
    await vi.waitFor(() => expect(section(view).querySelectorAll('li.view').length).toBe(2));

    expect(text(section(view).querySelector('.view-status'))).toBe('Disabled');
  });

  test('are asked of the Engine with the same-origin address of the version, and nothing else is fetched for them', async () => {
    const view = await page();
    await vi.waitFor(() => expect(section(view).querySelectorAll('li.view').length).toBe(2));

    const asked = app.engine.received.filter((r) => /\/views/.test(r.path)).map((r) => `${r.method} ${r.path}`);
    expect(asked).toEqual([`GET /api/v1/definitions/${HASH}/shop/views?uploader=ada`]);
    expect(view.querySelector('script[src], link[href^="http"]')).toBeNull();
  });
});

describe('a view that is chosen', () => {
  test('is opened from the list: the address says which, and its inputs follow the parameters it declares', async () => {
    const view = await page();
    await vi.waitFor(() => expect(section(view).querySelector('li.view button.open')).not.toBeNull());

    await press(section(view), 'li.view[data-view="orders"] button.open');

    await vi.waitFor(() => expect(section(view).querySelector('form.view-params')).not.toBeNull());
    expect(search().get('view')).toBe('orders');
    expect(search().get('pipeline')).toBe('shop');
    expect(search().get('uploader')).toBe('ada');
    const form = section(view).querySelector('form.view-params')!;
    expect(form.querySelector('input[data-param="note"]')?.getAttribute('type')).toBe('text');
    expect(form.querySelector('input[data-param="total"]')?.getAttribute('inputmode')).toBe('numeric');
    const select = form.querySelector<HTMLSelectElement>('select[data-param="status"]')!;
    expect([...select.options].map((o) => text(o))).toEqual(['Any', 'open', 'paid']);
  });

  test('is read at once when none of its parameters is required, and a column has its label, in the order declared', async () => {
    const view = await page({ extra: 'view=orders' });
    await waitForTable(view);

    expect([...section(view).querySelectorAll('thead th')].map((th) => text(th))).toEqual([
      'Order',
      'Customer',
      'Status',
      'Placed',
      'Total',
      'Note',
    ]);
    expect(rows(view).length).toBe(50);
    expect(column(view, 0).slice(0, 3)).toEqual(['1', '2', '3']);
  });

  test('marks a required parameter, and is not read until it has a value', async () => {
    const view = await page({ extra: 'view=history' });
    await vi.waitFor(() => expect(section(view).querySelector('form.view-params')).not.toBeNull());

    const label = section(view).querySelector('label[for$="customer"]')!;
    expect(text(label)).toContain('required');
    expect(section(view).querySelector('[data-param="customer"]')!.getAttribute('aria-required')).toBe('true');
    expect(readsOf()).toEqual([]);

    await press(section(view), 'button.apply');
    expect(text(section(view).querySelector('[data-param-error="customer"]'))).toBe('customer is required.');
    expect(readsOf()).toEqual([]);

    await fill(section(view), 'customer', 'c3');
    await press(section(view), 'button.apply');
    await waitForTable(view);
    expect(search().get('param.customer')).toBe('c3');
    expect(column(view, 0).slice(0, 2)).toEqual(['3', '10']);
  });

  test('is asked for with the values typed, as param.<name>; a value that is empty is left out', async () => {
    const view = await page({ extra: 'view=orders' });
    await waitForTable(view);

    await fill(section(view), 'status', 'paid');
    await fill(section(view), 'note', '');
    await press(section(view), 'button.apply');

    await vi.waitFor(() => expect(readsOf().length).toBe(2));
    expect(readsOf().at(-1)!.path).toBe(`/api/v1/definitions/${HASH}/shop/views/orders?uploader=ada&param.status=paid`);
    await vi.waitFor(() => expect(column(view, 0).slice(0, 2)).toEqual(['2', '4']));
  });

  test('refuses an integer that is not a whole number, naming the parameter, and does not ask', async () => {
    const view = await page({ extra: 'view=orders' });
    await waitForTable(view);
    const before = readsOf().length;

    await fill(section(view), 'total', '1.5');
    await press(section(view), 'button.apply');

    expect(text(section(view).querySelector('[data-param-error="total"]'))).toBe('total must be a whole number.');
    expect(readsOf().length).toBe(before);
  });

  test('keeps the values in the address, so that a reload shows the same table', async () => {
    const view = await page({ extra: 'view=orders&param.status=paid&offset=50' });
    await waitForTable(view);

    expect(column(view, 0)[0]).toBe('102');
    expect(section(view).querySelector<HTMLSelectElement>('select[data-param="status"]')!.value).toBe('paid');
  });

  test('is closed to the list again', async () => {
    const view = await page({ extra: 'view=orders' });
    await waitForTable(view);

    await press(section(view), 'button.close');

    expect(search().get('view')).toBeNull();
    await vi.waitFor(() => expect(section(view).querySelector('table')).toBeNull());
    expect(section(view).querySelectorAll('li.view').length).toBe(2);
  });
});

describe('the pages of a table', () => {
  test('go on with the next page and back with the previous; the first has no previous', async () => {
    const view = await page({ extra: 'view=orders' });
    await waitForTable(view);

    expect(section(view).querySelector<HTMLButtonElement>('button.prev')!.disabled).toBe(true);
    expect(text(section(view).querySelector('.view-range'))).toBe('Rows 1–50');

    await press(section(view), 'button.next');
    await vi.waitFor(() => expect(column(view, 0)[0]).toBe('51'));
    expect(search().get('offset')).toBe('50');
    expect(text(section(view).querySelector('.view-range'))).toBe('Rows 51–100');
    expect(readsOf().at(-1)!.path).toContain('offset=50');

    await press(section(view), 'button.prev');
    await vi.waitFor(() => expect(column(view, 0)[0]).toBe('1'));
    expect(search().get('offset')).toBeNull();
  });

  test('cannot go on after the last: there is no next unless the Engine says more follow', async () => {
    const view = await page({ extra: 'view=orders&offset=100' });
    await waitForTable(view);

    expect(rows(view).length).toBe(20);
    expect(section(view).querySelector<HTMLButtonElement>('button.next')!.disabled).toBe(true);
    expect(section(view).querySelector<HTMLButtonElement>('button.prev')!.disabled).toBe(false);
    expect(text(section(view).querySelector('.view-range'))).toBe('Rows 101–120');
  });

  test('say that there is nothing, when there is nothing', async () => {
    const view = await page({ extra: 'view=orders&param.status=open&param.total=5' });

    await vi.waitFor(() => expect(text(section(view).querySelector('.view-empty'))).toBe('No rows.'));
    expect(section(view).querySelector('table tbody tr')).toBeNull();
  });

  test('begin again at the first page when other values are applied', async () => {
    const view = await page({ extra: 'view=orders&offset=50' });
    await waitForTable(view);

    await fill(section(view), 'status', 'paid');
    await press(section(view), 'button.apply');

    await vi.waitFor(() => expect(column(view, 0)[0]).toBe('2'));
    expect(search().get('offset')).toBeNull();
  });
});

describe('a cell', () => {
  const seedRows = (backend: TestApp['engine']['backend']) =>
    backend.views.seed('main-db', 'orders', [
      { id: 1, customer: 'c1', status: 'paid', placed: '2026-10-07T08:00:00Z', total: 10, note: null },
      { id: 2, customer: 'c2', status: 'refused', placed: 'last Tuesday', total: 20, note: 'x' },
      { id: 3, customer: 'c3', status: 'pending', placed: null, total: 30, note: 'x' },
      { id: 4, customer: 'c4', status: 'open', placed: '2026-10-07 08:00:00', total: 40, note: 'x' },
    ]);

  test('of a status is the value in words, in the tone the declaration gives it: a colour, a mark and a word', async () => {
    const view = await page({ extra: 'view=orders', seed: seedRows });
    await waitForTable(view);

    const cells = rows(view).map((row) => row.querySelectorAll('td')[2].querySelector('.status')!);
    expect(cells.map((c) => c.getAttribute('data-tone'))).toEqual(['POSITIVE', 'NEGATIVE', 'NEUTRAL', 'NEUTRAL']);
    const toneClass = (c: Element) => ['positive', 'negative', 'neutral'].find((name) => c.classList.contains(name));
    expect(cells.map(toneClass)).toEqual(['positive', 'negative', 'neutral', 'neutral']);
    expect(text(cells[0])).toContain('paid');
    expect(text(cells[0])).toContain('positive');
    expect(text(cells[1])).toContain('refused');
    expect(text(cells[1])).toContain('negative');
    expect(text(cells[2])).toContain('pending');
    expect(text(cells[2])).toContain('neutral');
    expect(cells[0].querySelector('.mark')!.textContent).not.toBe(cells[1].querySelector('.mark')!.textContent);
  });

  test('of a time is shown as local time in the language of the screen, with the value the database gave as its tooltip', async () => {
    const view = await page({ extra: 'view=orders', seed: seedRows });
    await waitForTable(view);

    const time = rows(view)[0].querySelectorAll('td')[3].querySelector('time')!;
    expect(new Date(time.getAttribute('datetime')!).toISOString()).toBe('2026-10-07T08:00:00.000Z');
    expect(time.getAttribute('title')).toBe('2026-10-07T08:00:00Z');
    expect(time.textContent).toContain('2026');
    expect(time.textContent).toContain('Oct');
  });

  test('of a time in the language of a person who reads Chinese is shown the way that language does', async () => {
    const view = await page({ extra: 'view=orders', languages: ['zh-TW'], seed: seedRows });
    await waitForTable(view);

    const time = rows(view)[0].querySelectorAll('td')[3].querySelector('time')!;
    expect(time.textContent).toContain('2026年10月');
  });

  test('of a time that does not say its zone, or is no time, is shown as it is, and a null as empty', async () => {
    const view = await page({ extra: 'view=orders', seed: seedRows });
    await waitForTable(view);

    expect(column(view, 3)[1]).toBe('last Tuesday');
    expect(column(view, 3)[3]).toBe('2026-10-07 08:00:00');
    expect(rows(view)[2].querySelectorAll('td')[3].querySelector('time')).toBeNull();
    expect(text(rows(view)[2].querySelectorAll('td')[3])).toContain('empty');
    expect(rows(view)[0].querySelectorAll('td')[5].querySelector('.empty')).not.toBeNull();
  });

  test('is plain text, whatever it looks like: markup, a script and an address are not made into anything', async () => {
    const hostile = '<b onclick="alert(1)">bold</b> <script>alert(1)</script> <img src=x onerror=alert(1)> https://evil.example/x [a](https://evil.example)';
    const view = await page({
      extra: 'view=orders',
      seed: (backend) =>
        backend.views.seed('main-db', 'orders', [
          { id: 1, customer: hostile, status: hostile, placed: hostile, total: 1, note: hostile },
        ]),
    });
    await waitForTable(view);

    const table = section(view).querySelector('table')!;
    expect(table.querySelector('b, script, img, a, iframe')).toBeNull();
    const cells = [...rows(view)[0].querySelectorAll('td')].map((td) => text(td));
    expect(cells[1]).toBe(hostile);
    expect(cells[2]).toContain(hostile);
    expect(cells[3]).toBe(hostile);
    expect(cells[5]).toBe(hostile);
    expect(table.querySelectorAll('[onclick], [onerror]').length).toBe(0);
  });

  test('of a column with a drill is a button, not a link, and the other columns are not', async () => {
    const view = await page({ extra: 'view=orders' });
    await waitForTable(view);

    const first = rows(view)[0].querySelectorAll('td');
    expect(first[1].querySelector('button.drill')).not.toBeNull();
    expect(first[1].querySelector('a')).toBeNull();
    expect(first[0].querySelector('button')).toBeNull();
    expect(first[5].querySelector('button')).toBeNull();
  });

  test('of a column with a drill, with nothing in it, is not a button', async () => {
    const view = await page({
      extra: 'view=orders',
      seed: (backend) => backend.views.seed('main-db', 'orders', [{ id: 1, customer: null, status: 'open', total: 1 }]),
    });
    await waitForTable(view);

    expect(rows(view)[0].querySelectorAll('td')[1].querySelector('button')).toBeNull();
  });
});

describe('the title and the labels', () => {
  test('are text of the pipeline in one language, shown as they are, as text, whatever the language of the screen', async () => {
    const markup: FakePipeline = {
      ...shop,
      views: [
        {
          ...shop.views![0],
          title: '<img src=x onerror=alert(1)>訂單',
          description: '<script>1</script>',
          columns: [{ name: 'id', label: '<b>編號</b>', type: 'INTEGER' }],
          parameters: [{ name: 'note', type: 'TEXT' }],
        },
      ],
    };
    const view = await page({ pipeline: markup, languages: ['zh-TW'], extra: 'view=orders' });
    await waitForTable(view);

    expect(section(view).querySelector('img, script, b')).toBeNull();
    expect(text(section(view).querySelector('.view-heading'))).toBe('<img src=x onerror=alert(1)>訂單');
    expect(text(section(view).querySelector('thead th'))).toBe('<b>編號</b>');
  });
});

describe('a drill', () => {
  test('opens the view it points to with the value of the row as its parameter, and the address says so', async () => {
    const view = await page({ extra: 'view=orders' });
    await waitForTable(view);

    await press(rows(view)[2], 'button.drill');

    await vi.waitFor(() => expect(search().get('view')).toBe('history'));
    expect(search().get('param.customer')).toBe('c3');
    expect(search().get('offset')).toBeNull();
    await vi.waitFor(() => expect(text(section(view).querySelector('.view-heading'))).toBe('History of a customer'));
    await vi.waitFor(() => expect(column(view, 0).slice(0, 2)).toEqual(['3', '10']));
    expect(section(view).querySelector<HTMLInputElement>('[data-param="customer"]')!.value).toBe('c3');
  });

  test('is returned from to the view it came from, with the values and the page it had', async () => {
    const view = await page({ extra: 'view=orders&param.status=paid&offset=50' });
    await waitForTable(view);
    expect(column(view, 0)[0]).toBe('102');
    const customer = text(rows(view)[0].querySelectorAll('td')[1]);

    await press(rows(view)[0], 'button.drill');
    await vi.waitFor(() => expect(search().get('view')).toBe('history'));
    await vi.waitFor(() => expect(text(section(view).querySelector('.view-heading'))).toBe('History of a customer'));

    const back = section(view).querySelector<HTMLButtonElement>('button.back')!;
    expect(text(back)).toBe('Back to Orders');
    await press(section(view), 'button.back');

    await vi.waitFor(() => expect(text(section(view).querySelector('.view-heading'))).toBe('Orders'));
    await vi.waitFor(() => expect(column(view, 0)[0]).toBe('102'));
    expect(search().get('view')).toBe('orders');
    expect(search().get('param.status')).toBe('paid');
    expect(search().get('offset')).toBe('50');
    expect(section(view).querySelector<HTMLSelectElement>('select[data-param="status"]')!.value).toBe('paid');
    expect(text(rows(view)[0].querySelectorAll('td')[1])).toBe(customer);
    expect(section(view).querySelector('button.back')).toBeNull();
  });

  test('is returned from with the back button of the browser as well, to the same table', async () => {
    const view = await page({ extra: 'view=orders&param.status=paid&offset=50' });
    await waitForTable(view);
    await press(rows(view)[0], 'button.drill');
    await vi.waitFor(() => expect(search().get('view')).toBe('history'));

    history.back();

    await vi.waitFor(() => expect(search().get('view')).toBe('orders'));
    await vi.waitFor(() => expect(column(view, 0)[0]).toBe('102'));
    expect(search().get('param.status')).toBe('paid');
  });

  test('has no way back when the view was opened by its address and not by a drill', async () => {
    const view = await page({ extra: 'view=history&param.customer=c1' });
    await waitForTable(view);

    expect(section(view).querySelector('button.back')).toBeNull();
  });
});

describe('what goes wrong with a read', () => {
  const failing: Array<[ViewFailure, string, string]> = [
    ['timeout', 'took too long', '讀取逾時'],
    ['busy', 'busy', '忙碌'],
    ['connection_failed', 'cannot be reached', '無法連線'],
    ['schema_mismatch', 'not in the database', '資料庫中還沒有'],
    ['query_rejected', 'did not accept', '資料庫不接受'],
    ['too_large', 'too large', '資料量太大'],
    ['internal', 'failed unexpectedly', '未預期'],
  ];

  test.each(failing)('%s is said in words with a way to try again, in English, and a retry reads again', async (kind, english) => {
    const view = await page({ extra: 'view=orders', seed: (backend) => backend.views.failNext('main-db', kind) });

    await vi.waitFor(() => expect(section(view).querySelector('.view-error')).not.toBeNull());
    const error = section(view).querySelector<HTMLElement>('.view-error')!;
    expect(text(error)).toContain(english);
    expect(text(error)).not.toMatch(/[{}]|"error"/);
    expect(section(view).querySelector('table')).toBeNull();

    await press(error, 'button.retry');
    await waitForTable(view);
    expect(section(view).querySelector('.view-error')).toBeNull();
  });

  test.each(failing)('%s is said in Chinese as well', async (kind, _english, chinese) => {
    const view = await page({
      extra: 'view=orders',
      languages: ['zh-TW'],
      seed: (backend) => backend.views.failNext('main-db', kind),
    });

    await vi.waitFor(() => expect(section(view).querySelector('.view-error')).not.toBeNull());
    const error = section(view).querySelector<HTMLElement>('.view-error')!;
    expect(text(error)).toContain(chinese);
    expect(text(error)).not.toMatch(/[{}]|"error"/);
    expect(error.querySelector('button.retry')!.textContent!.trim()).toBe('重試');
  });

  test('a role that is not bound, when the address asks for the view all the same, is said with what to do and where', async () => {
    const view = await page({ extra: 'view=orders', bind: false });

    await vi.waitFor(() => expect(section(view).querySelector('.view-error')).not.toBeNull());
    const error = section(view).querySelector<HTMLElement>('.view-error')!;
    expect(text(error)).toContain('No shared resource is bound');
    expect(error.querySelector('a')?.getAttribute('href')).toContain('role=shop-db');
    expect(text(error)).not.toMatch(/[{}]|"error"/);
  });

  test('a role that is disabled is said so in Chinese', async () => {
    const view = await page({
      extra: 'view=orders',
      languages: ['zh-TW'],
      seed: (backend) => backend.resources.define('main-db', { type: 'jdbc-pool', enabled: false }),
    });

    await vi.waitFor(() => expect(section(view).querySelector('.view-error')).not.toBeNull());
    expect(text(section(view).querySelector('.view-error'))).toContain('已停用');
  });

  test('a value in the address that the view does not take is said at its field, without the value, and is not asked of the Engine', async () => {
    const view = await page({ extra: 'view=orders&param.status=bogus-value' });

    await vi.waitFor(() => expect(section(view).querySelector('[data-param-error="status"]')).not.toBeNull());
    const error = text(section(view).querySelector('[data-param-error="status"]'));
    expect(error).toBe('status must be one of the listed values.');
    expect(error).not.toContain('bogus-value');
    expect(readsOf()).toEqual([]);
    expect(section(view).querySelector('table')).toBeNull();
  });

  test('a value that the Engine refuses all the same is said with the name of the parameter and why, not the value', async () => {
    const view = await page({ extra: 'view=orders' });
    await waitForTable(view);
    app.engine.faults.push({
      match: /GET .*\/views\/orders/,
      status: 400,
      body: { error: 'invalid_view_parameter', message: 'x', parameter: 'total', reason: 'invalid_type' },
      times: 1,
    });

    await press(section(view), 'button.next');

    await vi.waitFor(() => expect(section(view).querySelector('.view-error')).not.toBeNull());
    const error = text(section(view).querySelector('.view-error'));
    expect(error).toContain('total must be a whole number.');
    expect(error).toContain('Change the values above');
  });

  test('an Engine that cannot be reached is said as that, with a retry', async () => {
    const view = await page({ extra: 'view=orders' });
    await waitForTable(view);
    app.engine.faults.push({ match: /GET .*\/views\/orders/, status: 0, drop: true, times: 1 });

    await press(section(view), 'button.next');

    await vi.waitFor(() => expect(section(view).querySelector('.view-error')).not.toBeNull());
    expect(text(section(view).querySelector('.view-error'))).toContain('could not be reached');
    expect(section(view).querySelector('.view-error button.retry')).not.toBeNull();
  });

  test('a view the version does not declare is said so', async () => {
    const view = await page({ extra: 'view=nope' });

    await vi.waitFor(() => expect(section(view).querySelector('.view-error')).not.toBeNull());
    expect(text(section(view).querySelector('.view-error'))).toContain('no such view');
  });
});

describe('who sees the views', () => {
  test('a developer sees those of a version of their own', async () => {
    const view = await page({ identity: bob, uploaders: ['bob'] });
    await vi.waitFor(() => expect(section(view).querySelectorAll('li.view').length).toBe(2));
  });

  test('a developer sees nothing of another person\'s version: the page says it is not there, and no view is asked for', async () => {
    app = await createTestApp({ identity: bob });
    app.engine.backend.seedArtifact('ada', [shop], { contentHash: HASH });
    app.context.router.navigate('/pipelines/x?pipeline=shop&uploader=ada');
    const view = app.mount(PipelineDetailPage, { contentHash: HASH });

    await vi.waitFor(() => expect(view.querySelector('.rl-notice')).not.toBeNull());
    expect(view.querySelector('section.views')).toBeNull();
    expect(app.engine.received.some((r) => /\/views/.test(r.path))).toBe(false);
  });

  test('an admin who can see two uploaders of the version is asked whose first; the views are those of the one chosen', async () => {
    app = await createTestApp({ identity: root });
    app.engine.backend.seedArtifact('ada', [shop], { contentHash: HASH });
    app.engine.backend.seedArtifact('bob', [shop], { contentHash: HASH });
    app.engine.backend.resources.define('main-db', { type: 'jdbc-pool' });
    app.engine.backend.bindings.seed('bob', 'shop', 'shop-db', 'main-db');
    app.context.router.navigate('/pipelines/x?pipeline=shop');
    const view = app.mount(PipelineDetailPage, { contentHash: HASH });
    await vi.waitFor(() => expect(view.querySelector('.choose-version')).not.toBeNull());
    expect(view.querySelector('section.views')).toBeNull();

    app.context.router.navigate('/pipelines/x?pipeline=shop&uploader=bob');
    await vi.waitFor(() => expect(section(view).querySelectorAll('li.view').length).toBe(2));
    expect(text(section(view).querySelector('.view-status'))).toBe('Ready');

    app.context.router.navigate('/pipelines/x?pipeline=shop&uploader=ada');
    await vi.waitFor(() => expect(text(section(view).querySelector('.view-status'))).toBe('Not bound'));
  });

  test('an admin can read the rows of a version of another, naming whose', async () => {
    const view = await page({ identity: root, uploaders: ['ada'], uploader: 'ada', extra: 'view=orders' });
    await waitForTable(view);

    expect(readsOf().at(-1)!.path).toContain('uploader=ada');
  });
});

describe('the data section, in the language', () => {
  test('follows the language of the screen for its own words and not for the pipeline\'s', async () => {
    const view = await page({ languages: ['zh-TW'], extra: 'view=orders' });
    await waitForTable(view);

    expect(section(view).querySelector('h2')!.textContent).toBe('資料');
    expect(text(section(view).querySelector('.view-range'))).toBe('第 1–50 筆');
    expect(text(section(view).querySelector('button.next'))).toBe('下一頁');
    expect(text(section(view).querySelector('thead th'))).toBe('Order');
    expect(text(section(view).querySelector('.view-heading'))).toBe('Orders');
  });
});

describe('the table of a view on a narrow screen', () => {
  test('scrolls sideways in a container of its own, with the first column held', async () => {
    const view = await page({ extra: 'view=orders' });
    await waitForTable(view);

    const table = section(view).querySelector('table')!;
    expect(table.classList.contains('rl-sticky-first')).toBe(true);
    expect(table.closest('.rl-table-wrap')).not.toBeNull();
  });
});

describe('the popup of a view (#29)', () => {
  const dialog = (view: HTMLElement) => view.querySelector<HTMLElement>('[role="dialog"]');
  const nameOf = (view: HTMLElement) =>
    text(document.getElementById(dialog(view)!.getAttribute('aria-labelledby')!));
  const escape = (target: Element) =>
    target.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));

  test('is not there while no view is chosen, and no table is shown', async () => {
    const view = await page();
    await vi.waitFor(() => expect(section(view).querySelectorAll('li.view').length).toBe(2));

    expect(dialog(view)).toBeNull();
    expect(section(view).querySelector('table')).toBeNull();
  });

  test('opens when a view is chosen: named by the title of the view, with the list kept behind and the page not scrolling', async () => {
    const view = await page();
    await vi.waitFor(() => expect(section(view).querySelector('li.view button.open')).not.toBeNull());

    await press(section(view), 'li.view[data-view="orders"] button.open');

    await vi.waitFor(() => expect(dialog(view)).not.toBeNull());
    expect(nameOf(view)).toBe('Orders');
    expect(dialog(view)!.querySelector('form.view-params')).not.toBeNull();
    expect(section(view).querySelectorAll('li.view').length).toBe(2);
    expect(document.body.classList.contains('rl-modal-open')).toBe(true);
    expect(dialog(view)!.contains(document.activeElement)).toBe(true);
  });

  test('is open with its table when the address already names the view', async () => {
    const view = await page({ extra: 'view=orders' });
    await waitForTable(view);

    expect(dialog(view)).not.toBeNull();
    expect(dialog(view)!.querySelector('table')).not.toBeNull();
  });

  test('is closed by Escape: the address loses the view and the keyboard returns to the item that opened it', async () => {
    const view = await page();
    await vi.waitFor(() => expect(section(view).querySelector('li.view button.open')).not.toBeNull());
    const opener = section(view).querySelector<HTMLButtonElement>('li.view[data-view="orders"] button.open')!;
    opener.focus();
    opener.click();
    await vi.waitFor(() => expect(dialog(view)).not.toBeNull());

    escape(dialog(view)!);

    await vi.waitFor(() => expect(dialog(view)).toBeNull());
    expect(search().get('view')).toBeNull();
    expect(document.activeElement).toBe(opener);
    expect(document.body.classList.contains('rl-modal-open')).toBe(false);
  });

  test('is closed by its close button, from a view that a drill reached', async () => {
    const view = await page({ extra: 'view=orders' });
    await waitForTable(view);
    await press(rows(view)[0], 'button.drill');
    await vi.waitFor(() => expect(search().get('view')).toBe('history'));
    expect(dialog(view)).not.toBeNull();

    await press(dialog(view)!, 'button.close');

    await vi.waitFor(() => expect(dialog(view)).toBeNull());
    expect(search().get('view')).toBeNull();
    expect(search().get('param.customer')).toBeNull();
  });

  test('stays one popup through a drill and its return, its name following the view', async () => {
    const view = await page({ extra: 'view=orders' });
    await waitForTable(view);
    const first = dialog(view)!;

    await press(rows(view)[0], 'button.drill');
    await vi.waitFor(() => expect(nameOf(view)).toBe('History of a customer'));
    expect(dialog(view)).toBe(first);
    await press(dialog(view)!, 'button.back');

    await vi.waitFor(() => expect(nameOf(view)).toBe('Orders'));
    expect(dialog(view)).toBe(first);
  });

  test('is closed by the back button of the browser, and opened again by forward', async () => {
    const view = await page();
    await vi.waitFor(() => expect(section(view).querySelector('li.view button.open')).not.toBeNull());
    await press(section(view), 'li.view[data-view="orders"] button.open');
    await vi.waitFor(() => expect(dialog(view)).not.toBeNull());

    history.back();
    await vi.waitFor(() => expect(dialog(view)).toBeNull());
    history.forward();
    await vi.waitFor(() => expect(dialog(view)).not.toBeNull());
  });
});
