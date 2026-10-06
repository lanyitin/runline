import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { fakeJar } from '../../test-support/fake-jars';
import PipelinesPage from './PipelinesPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const ada = { name: 'ada', role: 'developer' as const };
const root = { name: 'root', role: 'admin' as const };
const HASH_A = 'a1b2c3d4'.padEnd(64, '0');
const HASH_B = 'b9e8f7a6'.padEnd(64, '1');

const page = async (
  seed: (engine: TestApp['engine']['backend']) => void = () => {},
  options: Parameters<typeof createTestApp>[0] = { identity: ada },
) => {
  app = await createTestApp(options);
  seed(app.engine.backend);
  const view = app.mount(PipelinesPage);
  return view;
};
const rows = (view: HTMLElement) => [...view.querySelectorAll('tbody tr')] as HTMLElement[];
const names = (view: HTMLElement) => rows(view).map((r) => r.querySelector('.name')!.textContent);
const loaded = (view: HTMLElement) =>
  vi.waitFor(() => expect(view.querySelector('table, .rl-empty')).not.toBeNull());

const two = (engine: TestApp['engine']['backend']) => {
  engine.seedArtifact('ada', [{ name: 'order-sync', className: 'com.acme.OrderSync' }], {
    contentHash: HASH_A,
    uploadedAt: '2026-10-04T08:00:00Z',
  });
  engine.seedArtifact(
    'bob',
    [
      {
        name: 'risky',
        className: 'com.acme.Risky',
        reasons: [{ kind: 'JVM_EXIT', member: 'System.exit' }],
      },
    ],
    { contentHash: HASH_B, uploadedAt: '2026-10-05T09:00:00Z' },
  );
};

describe('the pipelines page', () => {
  test('lists each pipeline with its class, verdict, version, uploader, time and allow-list version', async () => {
    const view = await page(two, { identity: root });
    await loaded(view);

    expect(view.querySelector('h1')!.textContent).toBe('Pipelines');
    const row = rows(view).find((r) => r.textContent!.includes('order-sync'))!;
    expect(row.querySelector('.name')!.textContent).toBe('order-sync');
    expect(row.querySelector('.class')!.textContent).toBe('com.acme.OrderSync');
    expect(row.querySelector('.badge')!.textContent).toContain('SAFE');
    expect(row.querySelector('.hash')!.textContent).toBe('a1b2c3d');
    expect(row.querySelector('.hash')!.getAttribute('title')).toBe(HASH_A);
    expect(row.textContent).toContain('ada');
    expect(row.querySelector('time')!.getAttribute('datetime')).toBe('2026-10-04T08:00:00Z');
    expect(row.querySelector('.allow-list')!.textContent).toBe('1');
    const risky = rows(view).find((r) => r.textContent!.includes('risky'))!;
    expect(risky.querySelector('.badge')!.textContent).toContain('UNSAFE');
  });

  test('has the newest version first', async () => {
    const view = await page(two, { identity: root });
    await loaded(view);
    expect(names(view)).toEqual(['risky', 'order-sync']);
  });

  test('links to the details of the pipeline and to creating a run from it', async () => {
    const view = await page(two, { identity: root });
    await loaded(view);
    const row = rows(view).find((r) => r.textContent!.includes('order-sync'))!;
    const links = [...row.querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(links).toContain(`/pipelines/${HASH_A}?pipeline=order-sync`);
    expect(links).toContain(`/runs/new?contentHash=${HASH_A}&pipeline=order-sync`);
  });

  test('shows what the Engine gives the caller and no more: a developer has only their own', async () => {
    const view = await page(two);
    await loaded(view);
    expect(names(view)).toEqual(['order-sync']);
  });

  test('can be filtered by verdict, by uploader and by a word of the name or class', async () => {
    const view = await page(two, { identity: root });
    await loaded(view);

    const select = (label: string) =>
      [...view.querySelectorAll('select')].find((s) => s.labels?.[0]?.textContent === label)!;
    const choose = async (element: HTMLSelectElement, value: string) => {
      element.value = value;
      element.dispatchEvent(new Event('change', { bubbles: true }));
      await vi.waitFor(() => expect(true).toBe(true));
    };

    await choose(select('Verdict'), 'UNSAFE');
    await vi.waitFor(() => expect(names(view)).toEqual(['risky']));
    await choose(select('Verdict'), '');
    await choose(select('Uploaded by'), 'ada');
    await vi.waitFor(() => expect(names(view)).toEqual(['order-sync']));
    await choose(select('Uploaded by'), '');

    const search = view.querySelector<HTMLInputElement>('input[type="search"]')!;
    search.value = 'acme.Risky';
    search.dispatchEvent(new Event('input', { bubbles: true }));
    await vi.waitFor(() => expect(names(view)).toEqual(['risky']));

    search.value = 'nothing like it';
    search.dispatchEvent(new Event('input', { bubbles: true }));
    await vi.waitFor(() => expect(view.textContent).toContain('No pipeline matches the filters.'));
  });

  test('says that there is nothing yet, and where to upload', async () => {
    const view = await page();
    await loaded(view);
    expect(view.querySelector('.rl-empty')!.textContent).toContain('No pipelines yet');
    expect([...view.querySelectorAll('a')].some((a) => a.getAttribute('href') === '/upload')).toBe(
      true,
    );
  });

  test('is paged: 25 a page, with the way to the others, and says which are shown', async () => {
    const view = await page((engine) => {
      for (let i = 1; i <= 30; i += 1) {
        engine.seedArtifact(
          'ada',
          [{ name: `p-${String(i).padStart(2, '0')}`, className: 'x.P' }],
          {
            uploadedAt: `2026-10-04T08:${String(i).padStart(2, '0')}:00Z`,
          },
        );
      }
    });
    await loaded(view);
    expect(rows(view)).toHaveLength(25);
    expect(view.querySelector('.pager')!.textContent).toContain('Showing 1–25 of 30');

    view.querySelector<HTMLButtonElement>('button.next')!.click();
    await vi.waitFor(() => expect(rows(view)).toHaveLength(5));
    expect(view.querySelector('.pager')!.textContent).toContain('Showing 26–30 of 30');
    expect(view.querySelector<HTMLButtonElement>('button.next')!.disabled).toBe(true);
  });

  test('shows the words of the Engine about what the verdicts do not cover, as text', async () => {
    const view = await page(two);
    await loaded(view);
    expect(view.querySelector('.limitations')!.textContent).toContain('class references');
  });

  test('puts what a pipeline or an uploader calls itself in the page as text, never as markup', async () => {
    const view = await page(
      (engine) => {
        engine.seedArtifact('<img src=x onerror=alert(1)>', [
          { name: 'ok', className: '<script>alert(2)</script>' },
        ]);
      },
      { identity: root },
    );
    await loaded(view);
    expect(view.querySelector('img')).toBeNull();
    expect(view.querySelector('script')).toBeNull();
    expect(view.textContent).toContain('<script>alert(2)</script>');
    expect(view.textContent).toContain('<img src=x onerror=alert(1)>');
  });

  test('says in words that it is reading, then shows the failure and reads again on request', async () => {
    app = await createTestApp({ identity: ada });
    app.engine.faults.push({
      match: /GET \/api\/v1\/definitions/,
      status: 500,
      body: { error: 'internal_error', message: 'boom', errorId: 'e-5' },
      times: 1,
    });
    const view = app.mount(PipelinesPage);
    expect(view.textContent).toContain('Loading…');

    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')).not.toBeNull());
    expect(view.querySelector('[role="alert"]')!.textContent).toContain('e-5');

    view.querySelector<HTMLButtonElement>('button.retry')!.click();
    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')).toBeNull());
    expect(view.querySelector('.rl-empty')).not.toBeNull();
  });

  test('follows the language', async () => {
    const view = await page(two, { identity: ada, languages: ['zh-TW'] });
    await loaded(view);
    expect(view.querySelector('h1')!.textContent).toBe('Pipelines 定義集');
    expect(view.querySelector('th')!.textContent).toBe('Pipeline');
    expect(view.textContent).toContain('上傳 jar');
    expect(view.querySelector('.badge')!.textContent).toContain('SAFE');
  });

  test('a jar of several pipelines is several rows of one version', async () => {
    const view = await page((engine) => {
      engine.seedArtifact(
        'ada',
        [
          { name: 'one', className: 'x.One' },
          { name: 'two', className: 'x.Two' },
        ],
        { contentHash: HASH_A },
      );
    });
    await loaded(view);
    expect(names(view).sort()).toEqual(['one', 'two']);
    expect(new Set(rows(view).map((r) => r.querySelector('.hash')!.textContent)).size).toBe(1);
  });
});

test('the jar of the fake is what the page shows after an upload', async () => {
  app = await createTestApp({ identity: ada });
  await app.context.api.uploadJar(
    new File([fakeJar([{ name: 'fresh', className: 'x.F' }]) as BlobPart], 'f.jar'),
  );
  const view = app.mount(PipelinesPage);
  await vi.waitFor(() => expect(names(view)).toEqual(['fresh']));
});
