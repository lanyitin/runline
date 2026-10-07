import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { demoJars } from '../../test-support/fake-jars';
import CreateRunPage from './CreateRunPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const ada = { name: 'ada', role: 'developer' as const };
const root = { name: 'root', role: 'admin' as const };
const HASH = 'ab12cd34'.padEnd(64, '5');
/** What the list of pipelines gives as the value of the choice: a version and a pipeline in it. */
const choice = (hash: string, uploader: string, name: string) => JSON.stringify([hash, uploader, name]);

const sync = {
  name: 'order-sync',
  className: 'com.acme.OrderSync',
  parameters: [
    { name: 'region', required: true },
    { name: 'batch', required: false, default: '100' },
  ],
};

const page = async (
  options: {
    query?: string;
    languages?: string[];
    identity?: { name: string; role: 'admin' | 'developer' };
    seed?: (backend: TestApp['engine']['backend']) => void;
  } = {},
) => {
  app = await createTestApp({ identity: options.identity ?? ada, languages: options.languages });
  app.engine.backend.autoRun = false;
  if (options.seed) options.seed(app.engine.backend);
  else app.engine.backend.seedArtifact('ada', [sync], { contentHash: HASH });
  app.context.router.navigate(
    `/runs/new${options.query ?? `?contentHash=${HASH}&pipeline=order-sync`}`,
  );
  return app.mount(CreateRunPage);
};
const ready = (view: HTMLElement) =>
  vi.waitFor(() => expect(view.querySelector('select, .rl-empty')).not.toBeNull());
const field = (view: HTMLElement, name: string) =>
  view.querySelector<HTMLInputElement>(`input[name="param.${name}"]`)!;
const type = (input: HTMLInputElement, value: string) => {
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
};
const submit = (view: HTMLElement) => view.querySelector<HTMLButtonElement>('button.submit')!;
const posts = () =>
  app.engine.log.filter((r) => r.path === '/api/v1/runs' && r.authorization !== null).length;
const runsMade = () => app.engine.backend.runs;

describe('the pipeline to run', () => {
  test('is chosen from the pipelines the caller may use; with none chosen there is no form yet', async () => {
    const view = await page({ query: '' });
    await ready(view);

    expect(view.querySelector('h1')!.textContent).toBe('Create run');
    const select = view.querySelector<HTMLSelectElement>('select')!;
    expect([...select.options].map((o) => o.textContent!.trim())).toEqual([
      'Choose a pipeline',
      'order-sync · ab12cd3 · ada',
    ]);
    expect(view.querySelector('form .params')).toBeNull();
    expect(submit(view).disabled).toBe(true);
  });

  test('chosen from the address, with the form for its parameters; choosing another changes the address', async () => {
    const view = await page({
      seed: (b) => {
        b.seedArtifact('ada', [sync, { name: 'plain', className: 'x.Plain' }], {
          contentHash: HASH,
        });
      },
    });
    await ready(view);
    expect(view.querySelector<HTMLSelectElement>('select')!.value).toBe(
      choice(HASH, 'ada', 'order-sync'),
    );
    expect(field(view, 'region')).not.toBeNull();

    const select = view.querySelector<HTMLSelectElement>('select')!;
    select.value = choice(HASH, 'ada', 'plain');
    select.dispatchEvent(new Event('change', { bubbles: true }));

    await vi.waitFor(() => expect(field(view, 'region')).toBeNull());
    expect(view.textContent).toContain('This pipeline declares no parameters.');
    expect(location.search).toBe(`?contentHash=${HASH}&pipeline=plain&uploader=ada`);
  });

  test('says when the pipeline of the address is not one the caller may use, and offers the others', async () => {
    const view = await page({ query: `?contentHash=${'f'.repeat(64)}&pipeline=nope` });
    await ready(view);
    expect(view.textContent).toContain('was not found among the pipelines you may see');
    expect(view.querySelector('select')).not.toBeNull();
  });

  test('says there is nothing to run, and where to upload, when there is no pipeline at all', async () => {
    const view = await page({ query: '', seed: () => {} });
    await ready(view);
    expect(view.querySelector('.rl-empty')!.textContent).toContain(
      'There is no pipeline to run yet',
    );
    expect([...view.querySelectorAll('a')].some((a) => a.getAttribute('href') === '/upload')).toBe(
      true,
    );
  });
});

describe('the same content uploaded by two people', () => {
  const both = (b: TestApp['engine']['backend']) => {
    b.seedArtifact('ada', [sync], { contentHash: HASH });
    b.seedArtifact('bob', [sync], { contentHash: HASH });
  };

  test('is two choices, each saying whose, and a developer has only their own', async () => {
    const admin = await page({ identity: root, seed: both, query: '' });
    await ready(admin);
    const options = [...admin.querySelectorAll<HTMLOptionElement>('option')].map((o) => o.textContent!.trim());
    expect(options).toEqual(['Choose a pipeline', 'order-sync · ab12cd3 · ada', 'order-sync · ab12cd3 · bob']);
  });

  test('the version the address names is the one that is run', async () => {
    const view = await page({
      identity: root,
      seed: both,
      query: `?contentHash=${HASH}&pipeline=order-sync&uploader=bob`,
    });
    await ready(view);
    expect(view.querySelector<HTMLSelectElement>('select')!.value).toBe(choice(HASH, 'bob', 'order-sync'));
    type(field(view, 'region'), 'eu');

    submit(view).click();

    await vi.waitFor(() => expect(runsMade()).toHaveLength(1));
    expect(runsMade()[0]).toMatchObject({ uploader: 'bob', source: { kind: 'MANUAL', name: 'root' } });
  });

  test('an address that does not say whose chooses nobody, and says so', async () => {
    const view = await page({ identity: root, seed: both });
    await ready(view);

    expect(view.querySelector<HTMLSelectElement>('select')!.value).toBe('');
    expect(view.textContent).toContain('Choose whose version');
    expect(view.querySelector('form .params')).toBeNull();
    expect(submit(view).disabled).toBe(true);
  });

  test('an address that says whose, for a developer, can only be their own', async () => {
    const view = await page({
      seed: both,
      query: `?contentHash=${HASH}&pipeline=order-sync&uploader=bob`,
    });
    await ready(view);

    expect(view.querySelector<HTMLSelectElement>('select')!.value).toBe('');
    expect(view.textContent).toContain('was not found among the pipelines you may see');
    expect(runsMade()).toHaveLength(0);
  });
});

describe('the form of the parameters', () => {
  test('has a field for each declared parameter, whether it is required, and its default', async () => {
    const view = await page();
    await ready(view);

    const fields = [...view.querySelectorAll('.params .rl-field')];
    expect(fields).toHaveLength(2);
    expect(fields[0].querySelector('label')!.textContent).toContain('region');
    expect(fields[0].textContent).toContain('required');
    expect(fields[1].querySelector('label')!.textContent).toContain('batch');
    expect(fields[1].textContent).toContain('optional');
    expect(fields[1].textContent).toContain('Default: 100');
    expect(field(view, 'batch').placeholder).toBe('100');
    expect(fields[1].textContent).toContain('Left empty, the default is used.');
  });

  test('is filled in from the address: the way to run a run again with the same parameters', async () => {
    const view = await page({
      query: `?contentHash=${HASH}&pipeline=order-sync&param.region=eu&param.batch=7&param.nope=1`,
    });
    await ready(view);
    expect(field(view, 'region').value).toBe('eu');
    expect(field(view, 'batch').value).toBe('7');
  });

  test('does not create a run while a required parameter is empty, and says which', async () => {
    const view = await page();
    await ready(view);

    submit(view).click();

    await vi.waitFor(() => expect(view.querySelector('.rl-field-error')).not.toBeNull());
    expect(view.querySelector('.rl-field-error')!.textContent).toBe('This parameter is required.');
    expect(field(view, 'region').getAttribute('aria-invalid')).toBe('true');
    expect(posts()).toBe(0);
  });

  test('a required parameter that has a default may be left empty', async () => {
    const view = await page({
      seed: (b) => {
        b.seedArtifact(
          'ada',
          [
            {
              name: 'p',
              className: 'x.P',
              parameters: [{ name: 'q', required: true, default: 'd' }],
            },
          ],
          {
            contentHash: HASH,
          },
        );
      },
      query: `?contentHash=${HASH}&pipeline=p`,
    });
    await ready(view);
    submit(view).click();
    await vi.waitFor(() => expect(runsMade()).toHaveLength(1));
  });

  test('puts what the pipeline calls its parameters in the page as text', async () => {
    const view = await page({
      seed: (b) => {
        b.seedArtifact(
          'ada',
          [
            {
              name: 'p',
              className: '<img src=x onerror=alert(1)>',
              parameters: [{ name: '<b>q</b>', required: false, default: '<i>d</i>' }],
            },
          ],
          { contentHash: HASH },
        );
      },
      query: `?contentHash=${HASH}&pipeline=p`,
    });
    await ready(view);
    expect(view.querySelector('img, b, i')).toBeNull();
    expect(view.textContent).toContain('<b>q</b>');
    expect(view.textContent).toContain('Default: <i>d</i>');
  });
});

describe('creating the run', () => {
  test('sends the parameters that were filled in, leaves out the empty ones, and goes to the run', async () => {
    const view = await page();
    await ready(view);
    type(field(view, 'region'), 'eu-west');

    submit(view).click();

    await vi.waitFor(() => expect(runsMade()).toHaveLength(1));
    const run = runsMade()[0];
    expect(run.parameters).toEqual({ region: 'eu-west', batch: '100' });
    expect(run.contentHash).toBe(HASH);
    await vi.waitFor(() => expect(app.context.router.path).toBe(`/runs/${run.runId}`));
  });

  test('says that it is creating, and does not create twice', async () => {
    const view = await page();
    await ready(view);
    type(field(view, 'region'), 'eu');
    app.engine.mode = 'hang';

    submit(view).click();
    await vi.waitFor(() => expect(submit(view).textContent).toContain('Creating…'));
    expect(submit(view).disabled).toBe(true);
    submit(view).click();
    expect(posts()).toBe(1);
  });

  test('a problem the Engine finds with a parameter is shown at its field, and what it says in words above', async () => {
    const view = await page();
    await ready(view);
    type(field(view, 'region'), 'eu');
    app.engine.faults.push({
      match: /POST \/api\/v1\/runs/,
      status: 422,
      body: {
        error: 'invalid_parameters',
        message: 'm',
        problems: [
          { name: 'region', problem: 'missing' },
          { name: 'mystery', problem: 'undeclared' },
        ],
      },
      times: 1,
    });

    submit(view).click();

    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')).not.toBeNull());
    expect(view.querySelector('[role="alert"]')!.textContent).toContain(
      'The parameters do not match the pipeline',
    );
    const own = field(view, 'region').closest('.rl-field')!;
    expect(own.querySelector('.rl-field-error')!.textContent).toBe('This parameter is required.');
    expect(field(view, 'region').getAttribute('aria-invalid')).toBe('true');
    // the one that is no field of the form is in the box above
    expect(view.querySelector('[role="alert"] .problems')!.textContent).toBe(
      'mystery: not declared by the pipeline.',
    );
  });

  test('an error at a field goes when the field is changed', async () => {
    const view = await page();
    await ready(view);
    submit(view).click();
    await vi.waitFor(() => expect(view.querySelector('.rl-field-error')).not.toBeNull());

    type(field(view, 'region'), 'eu');

    await vi.waitFor(() => expect(view.querySelector('.rl-field-error')).toBeNull());
  });

  test('an UNSAFE pipeline that an admin has not allowed: said before, and when the Engine refuses, said with its words', async () => {
    const view = await page({
      seed: (b) => {
        b.seedArtifact(
          'ada',
          [{ name: 'risky', className: 'x.R', reasons: [{ kind: 'JVM_EXIT', member: 'm' }] }],
          {
            contentHash: HASH,
          },
        );
      },
      query: `?contentHash=${HASH}&pipeline=risky`,
    });
    await ready(view);
    expect(view.querySelector('.unsafe')!.textContent).toContain(
      'an admin has not allowed it to run',
    );

    submit(view).click();

    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')).not.toBeNull());
    expect(view.querySelector('[role="alert"]')!.textContent).toContain(
      'The pipeline is UNSAFE and an admin has not allowed unsafe execution.',
    );
    expect(runsMade()).toHaveLength(0);
  });

  test('an UNSAFE pipeline that an admin has allowed is run, and the page says it is allowed', async () => {
    const view = await page({
      seed: (b) => {
        b.seedArtifact(
          'ada',
          [{ name: 'risky', className: 'x.R', reasons: [{ kind: 'JVM_EXIT', member: 'm' }] }],
          {
            contentHash: HASH,
          },
        );
        b.allowUnsafe(HASH, 'risky');
      },
      query: `?contentHash=${HASH}&pipeline=risky`,
    });
    await ready(view);
    expect(view.querySelector('.unsafe')!.textContent).toContain('an admin has allowed it to run');
    submit(view).click();
    await vi.waitFor(() => expect(runsMade()).toHaveLength(1));
    expect(runsMade()[0].unsafeExecution).toMatchObject({ setBy: 'root' });
  });

  test('a shared resource that is not there: said with the name of each resource, and nothing is created', async () => {
    const view = await page({
      seed: (b) => {
        b.seedArtifact(
          'ada',
          [{ name: 'needs', className: 'x.N', resources: ['printer', 'erp-api'] }],
          {
            contentHash: HASH,
          },
        );
        b.defineResource('erp-api', false);
      },
      query: `?contentHash=${HASH}&pipeline=needs`,
    });
    await ready(view);
    expect(view.querySelectorAll('.warning-line').length).toBe(2);

    submit(view).click();

    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')).not.toBeNull());
    const alert = view.querySelector('[role="alert"]')!;
    expect(alert.textContent).toContain(
      'A shared resource the pipeline needs is not defined or is disabled.',
    );
    expect([...alert.querySelectorAll('.problems li')].map((li) => li.textContent)).toEqual([
      'printer: no such shared resource.',
      'erp-api: the shared resource is disabled.',
    ]);
    expect(runsMade()).toHaveLength(0);
  });

  test('a pipeline that has gone since the list was read: said as that', async () => {
    const view = await page();
    await ready(view);
    type(field(view, 'region'), 'eu');
    app.engine.backend.artifacts.clear();

    submit(view).click();

    await vi.waitFor(() => expect(view.querySelector('[role="alert"]')).not.toBeNull());
    expect(view.querySelector('[role="alert"]')!.textContent).toContain(
      'No such pipeline version.',
    );
  });

  test('an Engine that cannot be reached is said as that, and the form keeps what was typed', async () => {
    const view = await page();
    await ready(view);
    type(field(view, 'region'), 'eu');
    app.engine.faults.push({ match: /POST \/api\/v1\/runs/, status: 0, drop: true, times: 1 });

    submit(view).click();

    await vi.waitFor(() =>
      expect(view.querySelector('[role="alert"]')!.textContent).toContain('could not be reached'),
    );
    expect(field(view, 'region').value).toBe('eu');
    expect(submit(view).disabled).toBe(false);
  });

  test('follows the language', async () => {
    const view = await page({ languages: ['zh-TW'] });
    await ready(view);
    expect(view.querySelector('h1')!.textContent).toBe('建立 Run');
    expect(view.textContent).toContain('必填');
    expect(view.textContent).toContain('預設值：100');
  });
});

test('a pipeline that was just uploaded can be run at once', async () => {
  app = await createTestApp({ identity: ada });
  app.engine.backend.autoRun = false;
  const { artifact } = await app.context.api.uploadJar(
    new File([demoJars().slow as BlobPart], 's.jar'),
  );
  app.context.router.navigate(`/runs/new?contentHash=${artifact.contentHash}&pipeline=demo-slow`);
  const view = app.mount(CreateRunPage);
  await ready(view);
  submit(view).click();
  await vi.waitFor(() => expect(runsMade()).toHaveLength(1));
  expect(runsMade()[0].parameters).toEqual({ label: 'demo', steps: '30', delayMillis: '1000' });
});
