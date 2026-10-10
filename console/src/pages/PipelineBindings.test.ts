import { afterEach, describe, expect, test, vi } from 'vitest';
import { tick } from 'svelte';
import { createTestApp, type TestApp } from '../../test-support/app';
import type { FakePipeline } from '../../test-support/fake-jars';
import PipelineDetailPage from './PipelineDetailPage.svelte';

// The resource roles of a pipeline on its page (ADR-025, WI-1013): each role with its type, purpose
// and what it comes to, the choice of a shared resource for it, and who the bindings are of. The
// Fake Engine behind is held to the real one by the contract tests.

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };
const ada = { name: 'ada', role: 'developer' as const };
const HASH = 'b1d0'.padEnd(64, '7');

const pipeline: FakePipeline = {
  name: 'report',
  className: 'com.acme.Report',
  resources: ['report-store', 'ticket-counter', 'scratch'],
  resourceTypes: { 'report-store': 'file', 'ticket-counter': 'counter' },
  resourcePurposes: {
    'report-store': 'Where the weekly report is written',
    'ticket-counter': 'Limits how many tickets are handled at once',
  },
};

const page = async (
  options: {
    identity?: { name: string; role: 'admin' | 'developer' };
    uploaders?: string[];
    pipeline?: FakePipeline;
    languages?: string[];
    query?: string;
    seed?: (backend: TestApp['engine']['backend']) => void;
  } = {},
) => {
  app = await createTestApp({ identity: options.identity ?? ada, languages: options.languages });
  for (const who of options.uploaders ?? ['ada']) {
    app.engine.backend.seedArtifact(who, [options.pipeline ?? pipeline], { contentHash: HASH });
  }
  options.seed?.(app.engine.backend);
  app.context.router.navigate(options.query ?? '/pipelines/x?pipeline=report&uploader=ada');
  const view = app.mount(PipelineDetailPage, { contentHash: HASH });
  await vi.waitFor(() => expect(view.querySelectorAll('li.role').length).toBeGreaterThan(0));
  return view;
};
const section = (view: HTMLElement) => view.querySelector<HTMLElement>('section.bindings')!;
const role = (view: HTMLElement, name: string) =>
  view.querySelector<HTMLElement>(`li.role[data-role="${name}"]`)!;
const text = (element: Element | null) => element?.textContent?.replace(/\s+/g, ' ').trim() ?? '';
const optionsOf = (element: HTMLElement) =>
  [...element.querySelectorAll<HTMLOptionElement>('select option')].map((o) => text(o));
const choose = async (element: HTMLElement, value: string) => {
  const select = element.querySelector<HTMLSelectElement>('select')!;
  select.value = value;
  select.dispatchEvent(new Event('change', { bubbles: true }));
  await tick();
};
const press = async (element: HTMLElement, selector: string) => {
  const button = element.querySelector<HTMLButtonElement>(selector)!;
  expect(button, selector).not.toBeNull();
  button.click();
  await tick();
};
const store = (backend: TestApp['engine']['backend']) => {
  backend.resources.define('disk', { type: 'file' });
  backend.resources.define('disk-2', { type: 'file' });
  backend.resources.define('old-disk', { type: 'file', enabled: false });
  backend.resources.define('count', { type: 'counter' });
};

describe('the roles of a pipeline', () => {
  test('are listed, each with its name, the type it needs, what it is for and where it stands', async () => {
    const view = await page({ seed: store });

    expect([...view.querySelectorAll('li.role')].map((li) => li.getAttribute('data-role'))).toEqual([
      'report-store',
      'ticket-counter',
      'scratch',
    ]);
    const first = role(view, 'report-store');
    expect(text(first.querySelector('.role-name'))).toBe('report-store');
    expect(text(first.querySelector('.role-type'))).toBe('File');
    expect(text(first.querySelector('.role-purpose'))).toBe('Where the weekly report is written');
    expect(text(role(view, 'ticket-counter').querySelector('.role-type'))).toBe('Counter');
    expect(text(role(view, 'scratch').querySelector('.role-type'))).toBe('any type');
    expect(role(view, 'scratch').querySelector('.role-purpose')).toBeNull();
  });

  test('say, for a role with no binding and no resource of its name, that nothing is chosen and that no run can be created', async () => {
    const view = await page({ seed: store });

    const one = role(view, 'report-store');
    expect(text(one.querySelector('.role-state'))).toBe('Not bound');
    expect(text(one.querySelector('.role-current'))).toContain('no shared resource is named report-store');
    expect(text(one.querySelector('.role-problem'))).toContain('No run of this pipeline can be created');
    expect(text(section(view).querySelector('.cannot-run'))).toContain(
      'No run of this pipeline can be created yet',
    );
    expect(text(section(view).querySelector('.cannot-run'))).toContain('report-store');
  });

  test('take the resource of the same name for want of a binding, and say that it is the default', async () => {
    const view = await page({ seed: (backend) => backend.resources.define('scratch', { type: 'counter' }) });

    const one = role(view, 'scratch');
    expect(text(one.querySelector('.role-current'))).toContain('scratch');
    expect(text(one.querySelector('.role-current'))).toContain('same name');
    expect(text(one.querySelector('.role-state'))).toBe('Available');
    expect(one.querySelector('.role-problem')).toBeNull();
  });

  test('say what a bound role comes to: the resource, who bound it and when, and its state', async () => {
    const view = await page({
      seed: (backend) => {
        store(backend);
        backend.bindings.seed('ada', 'report', 'report-store', 'disk', 'ada');
        backend.bindings.seed('ada', 'report', 'ticket-counter', 'count', 'root');
        backend.resources.define('scratch');
      },
    });

    const one = role(view, 'report-store');
    expect(text(one.querySelector('.role-current'))).toContain('Bound to disk');
    expect(text(one.querySelector('.role-bound-by'))).toContain('ada');
    expect(one.querySelector('.role-bound-by time')).not.toBeNull();
    expect(text(one.querySelector('.role-state'))).toBe('Available');
    expect(text(role(view, 'ticket-counter').querySelector('.role-bound-by'))).toContain('root');
    expect(section(view).querySelector('.cannot-run')).toBeNull();
  });

  test('show a bound resource that is disabled, one that is gone and one that became another type, each as what is wrong', async () => {
    const view = await page({
      seed: (backend) => {
        store(backend);
        backend.bindings.seed('ada', 'report', 'report-store', 'old-disk');
        backend.bindings.seed('ada', 'report', 'ticket-counter', 'count');
        backend.bindings.seed('ada', 'report', 'scratch', 'vanished');
        backend.resources.define('count', { type: 'file' });
      },
    });

    expect(text(role(view, 'report-store').querySelector('.role-state'))).toBe('Disabled');
    expect(text(role(view, 'report-store').querySelector('.role-problem'))).toContain('old-disk is disabled');
    expect(text(role(view, 'ticket-counter').querySelector('.role-state'))).toBe('Another type');
    expect(text(role(view, 'ticket-counter').querySelector('.role-problem'))).toContain('count');
    expect(text(role(view, 'scratch').querySelector('.role-state'))).toBe('Not defined');
    expect(text(role(view, 'scratch').querySelector('.role-problem'))).toContain('vanished');
  });

  test('offer, for each role, the shared resources of its type and no others, the disabled ones marked', async () => {
    const view = await page({ seed: store });

    expect(optionsOf(role(view, 'report-store'))).toEqual([
      'Choose a shared resource',
      'disk',
      'disk-2',
      'old-disk (disabled)',
    ]);
    expect(optionsOf(role(view, 'ticket-counter'))).toEqual(['Choose a shared resource', 'count']);
    expect(optionsOf(role(view, 'scratch'))).toEqual([
      'Choose a shared resource',
      'count',
      'disk',
      'disk-2',
      'old-disk (disabled)',
    ]);
  });

  test('are bound by choosing a resource and saving; the answer is shown, and the role is now bound', async () => {
    const view = await page({ seed: store });
    const one = role(view, 'report-store');
    expect(one.querySelector<HTMLButtonElement>('button.save')!.disabled).toBe(true);
    expect(one.querySelector('button.unbind')).toBeNull();

    await choose(one, 'disk');
    expect(one.querySelector<HTMLButtonElement>('button.save')!.disabled).toBe(false);
    await press(one, 'button.save');

    await vi.waitFor(() => expect(text(one.querySelector('.role-result'))).toBe('report-store is now bound to disk.'));
    expect(text(one.querySelector('.role-current'))).toContain('Bound to disk');
    expect(text(one.querySelector('.role-bound-by'))).toContain('ada');
    expect(one.querySelector('button.unbind')).not.toBeNull();
    expect(app.engine.received.at(-1)).toMatchObject({
      method: 'PUT',
      path: `/api/v1/definitions/${HASH}/report/resource-bindings/report-store?uploader=ada`,
      body: JSON.stringify({ resource: 'disk' }),
    });
  });

  test('are bound to another resource by choosing it: the binding is replaced', async () => {
    const view = await page({
      seed: (backend) => {
        store(backend);
        backend.bindings.seed('ada', 'report', 'report-store', 'disk');
      },
    });
    const one = role(view, 'report-store');

    await choose(one, 'disk-2');
    await press(one, 'button.save');

    await vi.waitFor(() => expect(text(one.querySelector('.role-current'))).toContain('Bound to disk-2'));
    expect(app.engine.backend.bindings.rolesOf('ada', app.engine.backend.definitionOf(HASH, 'ada', 'report')!)[0].resource).toBe('disk-2');
  });

  test('are unbound by the button: the role goes back to the resource of its name', async () => {
    const view = await page({
      seed: (backend) => {
        store(backend);
        backend.resources.define('report-store', { type: 'file' });
        backend.bindings.seed('ada', 'report', 'report-store', 'disk');
      },
    });
    const one = role(view, 'report-store');
    expect(text(one.querySelector('.role-current'))).toContain('Bound to disk');

    await press(one, 'button.unbind');

    await vi.waitFor(() => expect(text(one.querySelector('.role-result'))).toBe('report-store is not bound any more.'));
    expect(text(one.querySelector('.role-current'))).toContain('same name');
    expect(one.querySelector('button.unbind')).toBeNull();
    expect(app.engine.received.at(-1)).toMatchObject({ method: 'DELETE' });
  });

  test('keep the person where they were when a binding is refused, with the reason in words', async () => {
    const view = await page({ seed: store });
    const one = role(view, 'report-store');
    app.engine.faults.push({
      match: /PUT .*resource-bindings/,
      status: 422,
      body: { error: 'invalid_binding', message: 'x', problem: 'type_mismatch' },
      times: 1,
    });

    await choose(one, 'disk');
    await press(one, 'button.save');

    await vi.waitFor(() => expect(text(one.querySelector('.role-error'))).toContain('cannot be bound to this role'));
    expect(text(one.querySelector('.role-error'))).toContain('not of the type the role expects');
    expect(text(one.querySelector('.role-current'))).toContain('no shared resource is named');
    expect(one.querySelector<HTMLSelectElement>('select')!.value).toBe('disk');
  });

  test('say when the resource chosen is gone by the time it is saved (resource_not_found)', async () => {
    const view = await page({ seed: store });
    const one = role(view, 'report-store');
    await choose(one, 'disk');
    app.engine.backend.resources.define('disk-2', { type: 'file' });
    app.engine.faults.push({
      match: /PUT .*resource-bindings/,
      status: 404,
      body: { error: 'resource_not_found', message: 'x' },
      times: 1,
    });

    await press(one, 'button.save');

    await vi.waitFor(() => expect(text(one.querySelector('.role-error'))).toContain('No such shared resource'));
  });

  test('say, when there is no shared resource of the type, that there is none: a developer is told to ask an admin and is not led to define one', async () => {
    const view = await page({ seed: (backend) => backend.resources.define('count', { type: 'counter' }) });
    const one = role(view, 'report-store');

    expect(text(one.querySelector('.role-none'))).toContain('No shared resource of this type exists');
    expect(text(one.querySelector('.role-none'))).toContain('administrator');
    expect(section(view).querySelector('a[href="/resources"]')).toBeNull();
    expect(one.querySelector('select')).toBeNull();
  });

  test('lead an admin, when there is none of the type, to the page where resources are defined', async () => {
    const view = await page({
      identity: root,
      seed: (backend) => backend.resources.define('count', { type: 'counter' }),
    });

    expect(role(view, 'report-store').querySelector('.role-none a')!.getAttribute('href')).toBe('/resources');
  });
});

describe('whose bindings the roles show', () => {
  test('a developer sees their own and is not asked to choose an uploader', async () => {
    const view = await page({ seed: store });

    expect(section(view).querySelector('select.uploader')).toBeNull();
  });

  test('an admin who can see more than one uploader of the content chooses one and sees that uploader\'s bindings', async () => {
    const view = await page({
      identity: root,
      uploaders: ['ada', 'bob'],
      query: '/pipelines/x?pipeline=report&uploader=ada',
      seed: (backend) => {
        store(backend);
        backend.bindings.seed('ada', 'report', 'report-store', 'disk');
        backend.bindings.seed('bob', 'report', 'report-store', 'disk-2');
      },
    });

    const picker = section(view).querySelector<HTMLSelectElement>('select.uploader')!;
    expect([...picker.options].map((o) => o.textContent!.trim())).toEqual(['ada', 'bob']);
    expect(picker.value).toBe('ada');
    expect(text(role(view, 'report-store').querySelector('.role-current'))).toContain('Bound to disk');

    picker.value = 'bob';
    picker.dispatchEvent(new Event('change', { bubbles: true }));

    await vi.waitFor(() => expect(text(role(view, 'report-store').querySelector('.role-current'))).toContain('Bound to disk-2'));
    expect(app.context.router.search).toContain('uploader=bob');
    expect(section(view).querySelector<HTMLSelectElement>('select.uploader')!.value).toBe('bob');
  });

  test('an admin changes the bindings of the uploader chosen, and no other\'s', async () => {
    const view = await page({
      identity: root,
      uploaders: ['ada', 'bob'],
      query: '/pipelines/x?pipeline=report&uploader=bob',
      seed: store,
    });
    const one = role(view, 'report-store');

    await choose(one, 'disk');
    await press(one, 'button.save');

    await vi.waitFor(() => expect(text(one.querySelector('.role-bound-by'))).toContain('root'));
    expect(app.engine.received.at(-1)!.path).toContain('?uploader=bob');
    const definition = app.engine.backend.definitionOf(HASH, 'ada', 'report')!;
    expect(app.engine.backend.bindings.rolesOf('ada', definition)[0].binding).toBeNull();
    expect(app.engine.backend.bindings.rolesOf('bob', definition)[0].binding?.resource).toBe('disk');
  });
});

describe('the roles of a pipeline, as text from a jar', () => {
  test('a purpose, a role and a type that are markup are shown as text and nothing is made of them', async () => {
    const view = await page({
      pipeline: {
        name: 'report',
        className: 'x.R',
        resources: ['<b>r</b>'],
        resourceTypes: { '<b>r</b>': 'file' },
        resourcePurposes: { '<b>r</b>': '<img src=x onerror=alert(1)><script>1</script>' },
      },
    });

    expect(view.querySelector('img, script, b')).toBeNull();
    expect(text(view.querySelector('.role-purpose'))).toBe('<img src=x onerror=alert(1)><script>1</script>');
    expect(text(view.querySelector('.role-name'))).toBe('<b>r</b>');
  });
});

describe('the roles of a pipeline, around them', () => {
  test('say when the pipeline needs no shared resource', async () => {
    app = await createTestApp({ identity: ada });
    app.engine.backend.seedArtifact('ada', [{ name: 'bare', className: 'x.Bare' }], { contentHash: HASH });
    app.context.router.navigate('/pipelines/x?pipeline=bare&uploader=ada');
    const view = app.mount(PipelineDetailPage, { contentHash: HASH });
    await vi.waitFor(() => expect(view.querySelector('section.bindings')).not.toBeNull());
    await vi.waitFor(() => expect(text(section(view))).toContain('None needed.'));
  });

  test('bring to the role the address names: it is marked as the one to look at', async () => {
    const view = await page({ query: '/pipelines/x?pipeline=report&uploader=ada&role=scratch', seed: store });

    expect(role(view, 'scratch').getAttribute('aria-current')).toBe('true');
    expect(role(view, 'report-store').getAttribute('aria-current')).toBeNull();
  });

  test('list the bindings of roles this version no longer declares, which can be removed', async () => {
    const view = await page({
      seed: (backend) => {
        store(backend);
        backend.bindings.seed('ada', 'report', 'gone-role', 'disk');
      },
    });

    const stale = section(view).querySelector<HTMLElement>('.stale')!;
    expect(text(stale)).toContain('gone-role');
    expect(text(stale)).toContain('disk');

    await press(stale, 'button.remove');

    await vi.waitFor(() => expect(section(view).querySelector('.stale')).toBeNull());
    expect(app.engine.received.at(-1)).toMatchObject({ method: 'DELETE' });
  });

  test('say that the roles could not be read, with a way to try again', async () => {
    app = await createTestApp({ identity: ada });
    app.engine.backend.seedArtifact('ada', [pipeline], { contentHash: HASH });
    app.engine.faults.push({
      match: /GET .*resource-bindings/,
      status: 500,
      body: { error: 'internal_error', message: 'x' },
      times: 1,
    });
    app.context.router.navigate('/pipelines/x?pipeline=report&uploader=ada');
    const view = app.mount(PipelineDetailPage, { contentHash: HASH });

    await vi.waitFor(() => expect(section(view)).not.toBeNull());
    await vi.waitFor(() => expect(section(view).querySelector('[role="alert"]')).not.toBeNull());
    await press(section(view), 'button.retry');
    await vi.waitFor(() => expect(view.querySelectorAll('li.role').length).toBe(3));
  });

  test('follow the language', async () => {
    const view = await page({ languages: ['zh-TW'], seed: store });

    expect(text(role(view, 'report-store').querySelector('.role-state'))).toBe('未綁定');
    expect(text(role(view, 'report-store').querySelector('.role-problem'))).toContain('無法建立這個 pipeline 的 run');
    expect(optionsOf(role(view, 'report-store'))[0]).toBe('選擇共享資源');
    expect(optionsOf(role(view, 'report-store')).at(-1)).toBe('old-disk（已停用）');
  });
});
