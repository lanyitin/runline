import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import PipelineDetailPage from './PipelineDetailPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const ada = { name: 'ada', role: 'developer' as const };
const HASH = 'c0ffee12'.padEnd(64, '3');

const page = async (
  options: {
    pipelines?: Parameters<TestApp['engine']['backend']['seedArtifact']>[1];
    query?: string;
    hash?: string;
    languages?: string[];
  } = {},
) => {
  app = await createTestApp({ identity: ada, languages: options.languages });
  app.engine.backend.seedArtifact(
    'ada',
    options.pipelines ?? [
      {
        name: 'order-sync',
        className: 'com.acme.OrderSync',
        parameters: [
          { name: 'region', required: true },
          { name: 'batch', required: false, default: '100' },
        ],
        files: [{ scope: 'RUN_PRIVATE', mode: 'READ_WRITE' }],
        resources: ['erp-api'],
      },
    ],
    { contentHash: HASH, uploadedAt: '2026-10-05T01:00:00Z' },
  );
  app.context.router.navigate(options.query ?? '/pipelines/x?pipeline=order-sync');
  return app.mount(PipelineDetailPage, { contentHash: options.hash ?? HASH });
};
const ready = (view: HTMLElement) =>
  vi.waitFor(() => expect(view.querySelector('h1')).not.toBeNull());
const section = (view: HTMLElement, title: string) =>
  [...view.querySelectorAll('section')].find((s) => s.querySelector('h2')?.textContent === title)!;

describe('the page of a pipeline', () => {
  test('says what it is: name, class, verdict, the version with the full hash, its size, uploader, time and allow-list version', async () => {
    const view = await page();
    await ready(view);

    expect(view.querySelector('h1')!.textContent).toBe('order-sync');
    expect(view.textContent).toContain('com.acme.OrderSync');
    expect(view.querySelector('.badge')!.textContent).toContain('SAFE');
    const facts = view.querySelector('dl.facts')!;
    expect(facts.textContent).toContain(HASH);
    expect(facts.textContent).toContain('1,000 B');
    expect(facts.textContent).toContain('ada');
    expect(facts.querySelector('time')!.getAttribute('datetime')).toBe('2026-10-05T01:00:00Z');
    expect(facts.textContent).toContain('Judged under allow-list version');
  });

  test('has what the pipeline declares: parameters with required and default, files, network, processes, resources', async () => {
    const view = await page();
    await ready(view);

    const params = [...section(view, 'Parameters').querySelectorAll('tbody tr')].map((tr) =>
      [...tr.querySelectorAll('td')].map((td) => td.textContent!.trim()),
    );
    expect(params).toEqual([
      ['region', 'Required', ''],
      ['batch', 'Optional', '100'],
    ]);
    expect(section(view, 'Files').textContent).toContain('Private to one run');
    expect(section(view, 'Files').textContent).toContain('Read and write');
    expect(section(view, 'Network').textContent).toContain('None');
    expect(section(view, 'External processes').textContent).toContain('None');
    expect(section(view, 'Shared resources').textContent).toContain('erp-api');
  });

  test('says when there are no parameters, files or resources', async () => {
    const view = await page({
      pipelines: [{ name: 'bare', className: 'x.Bare' }],
      query: '/pipelines/x?pipeline=bare',
    });
    await ready(view);
    expect(section(view, 'Parameters').textContent).toContain('No parameters.');
    expect(section(view, 'Files').textContent).toContain('No file access.');
    expect(section(view, 'Shared resources').textContent).toContain('None needed.');
  });

  test('says that unrestricted access is unrestricted', async () => {
    const view = await page({
      pipelines: [
        { name: 'wide', className: 'x.W', networkUnrestricted: true, processesUnrestricted: true },
      ],
      query: '/pipelines/x?pipeline=wide',
    });
    await ready(view);
    expect(section(view, 'Network').textContent).toContain('Unrestricted');
    expect(section(view, 'External processes').textContent).toContain('Unrestricted');
  });

  test('says there is no reason against a SAFE pipeline', async () => {
    const view = await page();
    await ready(view);
    expect(section(view, 'Verdict').textContent).toContain('No reason was found');
  });

  test('gives each reason of an UNSAFE pipeline: its kind in words, what it is about, and the path from the pipeline', async () => {
    const view = await page({
      pipelines: [
        {
          name: 'risky',
          className: 'x.Risky',
          reasons: [
            { kind: 'UNRESTRICTED_ACCESS', category: 'NETWORK' },
            {
              kind: 'NOT_ALLOW_LISTED',
              className: 'java.io.File',
              path: ['x.Risky', 'x.Helper', 'java.io.File'],
            },
            { kind: 'JVM_EXIT', member: 'java/lang/System.exit(I)V', path: ['x.Risky'] },
            { kind: 'LIMIT_EXCEEDED', detail: 'too many classes' },
            { kind: 'SOMETHING_NEW', detail: 'a kind of a newer Engine' },
          ],
        },
      ],
      query: '/pipelines/x?pipeline=risky',
    });
    await ready(view);

    expect(view.querySelector('.badge')!.textContent).toContain('UNSAFE');
    const reasons = [...view.querySelectorAll('.reasons > li')];
    expect(reasons).toHaveLength(5);
    expect(reasons[0].textContent).toContain('Unrestricted network or process access');
    expect(reasons[0].textContent).toContain('Network');
    expect(reasons[1].textContent).toContain('Class outside the allow-list');
    expect(reasons[1].textContent).toContain('java.io.File');
    expect([...reasons[1].querySelectorAll('.path li')].map((li) => li.textContent)).toEqual([
      'x.Risky',
      'x.Helper',
      'java.io.File',
    ]);
    expect(reasons[2].textContent).toContain('java/lang/System.exit(I)V');
    expect(reasons[3].textContent).toContain('too many classes');
    expect(reasons[4].textContent).toContain('SOMETHING_NEW');
  });

  test('says the warnings by their kind, naming the resource, in the language of the screen', async () => {
    const view = await page({ query: '/pipelines/x?pipeline=order-sync' });
    await ready(view);
    expect(section(view, 'Warnings').textContent).toContain(
      'The shared resource erp-api is not defined',
    );
    app.engine.backend.defineResource('erp-api', false);
    app.context.i18n.setLocale('zh-TW');
    await vi.waitFor(() => expect(view.textContent).toContain('共享資源 erp-api 尚未定義'));
  });

  test('says whether an admin has allowed an UNSAFE pipeline to run', async () => {
    const view = await page({
      pipelines: [
        { name: 'risky', className: 'x.R', reasons: [{ kind: 'JVM_EXIT', member: 'm' }] },
      ],
      query: '/pipelines/x?pipeline=risky',
    });
    await ready(view);
    expect(view.textContent).toContain('has not allowed this pipeline to run');

    app.engine.backend.allowUnsafe(HASH, 'risky');
    app.mount(PipelineDetailPage, { contentHash: HASH });
    await vi.waitFor(() =>
      expect(document.body.textContent).toContain(
        'has allowed this pipeline to run although it is UNSAFE',
      ),
    );
  });

  test('has the words of the Engine about what the verdict does not cover, as text', async () => {
    const view = await page();
    await ready(view);
    expect(view.querySelector('.limitations')!.textContent).toContain('class references');
  });

  test('leads to creating a run of this pipeline', async () => {
    const view = await page();
    await ready(view);
    const link = [...view.querySelectorAll('a')].find(
      (a) => a.textContent!.trim() === 'Create run',
    )!;
    expect(link.getAttribute('href')).toBe(
      `/runs/new?contentHash=${HASH}&pipeline=order-sync&uploader=ada`,
    );
  });

  test("the version of another person is not found, whether or not the address names them", async () => {
    app = await createTestApp({ identity: ada });
    app.engine.backend.seedArtifact('bob', [{ name: 'order-sync', className: 'x.O' }], { contentHash: HASH });
    app.engine.backend.seedArtifact('ada', [{ name: 'order-sync', className: 'x.O' }], { contentHash: HASH });
    app.context.router.navigate('/pipelines/x?pipeline=order-sync&uploader=bob');

    const view = app.mount(PipelineDetailPage, { contentHash: HASH });

    await vi.waitFor(() => expect(view.querySelector('.rl-notice.warning')).not.toBeNull());
    expect(view.querySelector('h1')).toBeNull();
    expect(view.textContent).not.toContain('Choose whose version');
  });

  test('shows the pipeline the address names when the version has several, and links the others', async () => {
    const view = await page({
      pipelines: [
        { name: 'one', className: 'x.One' },
        { name: 'two', className: 'x.Two' },
      ],
      query: '/pipelines/x?pipeline=two',
    });
    await ready(view);
    expect(view.querySelector('h1')!.textContent).toBe('two');
    const links = [...view.querySelectorAll('.others a')].map((a) => a.getAttribute('href'));
    expect(links).toEqual([
      `/pipelines/${HASH}?pipeline=one&uploader=ada`,
      `/pipelines/${HASH}?pipeline=two&uploader=ada`,
    ]);
  });

  test('shows the first pipeline when the address names none', async () => {
    const view = await page({
      pipelines: [
        { name: 'one', className: 'x.One' },
        { name: 'two', className: 'x.Two' },
      ],
      query: '/pipelines/x',
    });
    await ready(view);
    expect(view.querySelector('h1')!.textContent).toBe('one');
  });

  test('says there is no such pipeline in the version, when the address names one that is not there', async () => {
    const view = await page({ query: '/pipelines/x?pipeline=nope' });
    await vi.waitFor(() =>
      expect(view.textContent).toContain('This version has no pipeline named nope.'),
    );
  });

  test("says there is no such version, as it says for one that is not the caller's to see", async () => {
    const view = await page({ hash: 'f'.repeat(64) });
    await vi.waitFor(() => expect(view.querySelector('.rl-notice')).not.toBeNull());
    expect(view.textContent).toContain('Version not found');
    expect(view.textContent).toContain('not one you may see');
  });

  test('puts everything the jar says in the page as text, never as markup', async () => {
    const view = await page({
      pipelines: [
        {
          name: 'x',
          className: '<img src=x onerror=alert(1)>',
          parameters: [{ name: '<b>p</b>', required: false, default: '<script>1</script>' }],
          reasons: [
            {
              kind: 'NOT_ALLOW_LISTED',
              className: '<i>c</i>',
              path: ['<u>a</u>'],
              detail: '<svg onload=1>',
            },
          ],
          resources: ['<a href=1>r</a>'],
        },
      ],
      query: '/pipelines/x?pipeline=x',
    });
    await ready(view);
    expect(view.querySelector('img, script, b, i, u, svg')).toBeNull();
    expect(view.textContent).toContain('<script>1</script>');
    expect(view.textContent).toContain('<svg onload=1>');
  });

  test('follows the language', async () => {
    const view = await page({ languages: ['zh-TW'] });
    await ready(view);
    expect(view.textContent).toContain('宣告的內容');
    expect(view.textContent).toContain('建立 run');
  });
});
