import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { demoJars, fakeJar } from '../../test-support/fake-jars';
import UploadPage from './UploadPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const ada = { name: 'ada', role: 'developer' as const };

const page = async (languages = ['en']) => {
  app = await createTestApp({ identity: ada, languages });
  return app.mount(UploadPage);
};
const file = (bytes: Uint8Array, name = 'pipelines.jar') => new File([bytes as BlobPart], name);

/** Chooses the file the way a person does with the file dialog. */
const choose = (view: HTMLElement, chosen: File) => {
  const input = view.querySelector<HTMLInputElement>('input[type="file"]')!;
  Object.defineProperty(input, 'files', { value: [chosen], configurable: true });
  input.dispatchEvent(new Event('change', { bubbles: true }));
};
const drop = (view: HTMLElement, dropped: File) => {
  const zone = view.querySelector<HTMLElement>('.dropzone')!;
  const event = new Event('drop', { bubbles: true, cancelable: true });
  Object.defineProperty(event, 'dataTransfer', { value: { files: [dropped] } });
  zone.dispatchEvent(event);
};
const submit = (view: HTMLElement) => view.querySelector<HTMLButtonElement>('button.submit')!;
const alertText = (view: HTMLElement) => view.querySelector('[role="alert"]')?.textContent ?? '';
const uploadWith = async (view: HTMLElement, bytes: Uint8Array, name?: string) => {
  choose(view, file(bytes, name));
  await vi.waitFor(() => expect(submit(view).disabled).toBe(false));
  submit(view).click();
};

describe('choosing a jar', () => {
  test('asks for a file, and does not upload until there is one', async () => {
    const view = await page();
    expect(view.querySelector('h1')!.textContent).toBe('Upload');
    expect(view.textContent).toContain('Drop a jar here, or');
    expect(view.querySelector('input[type="file"]')).not.toBeNull();
    expect(submit(view).disabled).toBe(true);
  });

  test('shows the name and the size of the file that was chosen, as text', async () => {
    const view = await page();
    choose(view, file(new Uint8Array(2048), '<b>x</b>.jar'));
    await vi.waitFor(() => expect(view.querySelector('.chosen')).not.toBeNull());
    expect(view.querySelector('.chosen')!.textContent).toContain('<b>x</b>.jar');
    expect(view.querySelector('.chosen')!.textContent).toContain('2 KiB');
    expect(view.querySelector('.chosen b')).toBeNull();
    expect(submit(view).disabled).toBe(false);
  });

  test('takes a file that is dropped on it', async () => {
    const view = await page();
    drop(view, file(new Uint8Array(10), 'dropped.jar'));
    await vi.waitFor(() =>
      expect(view.querySelector('.chosen')!.textContent).toContain('dropped.jar'),
    );
  });

  test('lets the person choose another file', async () => {
    const view = await page();
    choose(view, file(new Uint8Array(10), 'one.jar'));
    await vi.waitFor(() => expect(view.querySelector('.chosen')).not.toBeNull());
    choose(view, file(new Uint8Array(10), 'two.jar'));
    await vi.waitFor(() => expect(view.querySelector('.chosen')!.textContent).toContain('two.jar'));
  });
});

describe('an upload that the Engine takes', () => {
  test('201: a new version is made, and each pipeline in it is shown with its verdict and where to go on', async () => {
    const view = await page();

    await uploadWith(view, demoJars().slow);

    await vi.waitFor(() => expect(view.querySelector('.result')).not.toBeNull());
    const result = view.querySelector('.result')!;
    expect(result.textContent).toContain('A new version was made');
    expect(result.textContent).not.toContain('uploaded before');
    expect(result.querySelector('.hash')!.textContent).toMatch(/^[0-9a-f]{7}$/);
    const line = result.querySelector('.pipeline-line')!;
    expect(line.textContent).toContain('demo-slow');
    expect(line.querySelector('.badge')!.textContent).toContain('SAFE');
    const hrefs = [...line.querySelectorAll('a')].map((a) => a.getAttribute('href'));
    expect(hrefs[0]).toMatch(/^\/pipelines\/[0-9a-f]{64}\?pipeline=demo-slow$/);
    expect(hrefs[1]).toMatch(/^\/runs\/new\?contentHash=[0-9a-f]{64}&pipeline=demo-slow$/);
  });

  test('200: the same bytes are the version that was there, and it says so, and no new version was made', async () => {
    const view = await page();
    await app.context.api.uploadJar(file(demoJars().slow));

    await uploadWith(view, demoJars().slow);

    await vi.waitFor(() => expect(view.querySelector('.result')).not.toBeNull());
    expect(view.querySelector('.result')!.textContent).toContain('This jar was uploaded before');
    expect(view.querySelector('.result')!.textContent).not.toContain('A new version was made');
    expect(app.engine.backend.artifacts.size).toBe(1);
  });

  test('says when a pipeline in the jar is UNSAFE and cannot run until an admin allows it', async () => {
    const view = await page();
    await uploadWith(view, demoJars().unsafe);
    await vi.waitFor(() => expect(view.querySelector('.result')).not.toBeNull());
    expect(view.querySelector('.result .badge')!.textContent).toContain('UNSAFE');
    expect(view.querySelector('.result')!.textContent).toContain(
      'cannot run until an admin allows it',
    );
  });

  test('shows every pipeline of a jar that has several', async () => {
    const view = await page();
    await uploadWith(
      view,
      fakeJar([
        { name: 'one', className: 'x.One' },
        { name: 'two', className: 'x.Two' },
      ]),
    );
    await vi.waitFor(() => expect(view.querySelectorAll('.pipeline-line')).toHaveLength(2));
    expect(view.querySelector('.result')!.textContent).toContain('2 pipelines in this version');
  });

  test('can go on to another jar', async () => {
    const view = await page();
    await uploadWith(view, demoJars().slow);
    await vi.waitFor(() => expect(view.querySelector('.result')).not.toBeNull());

    view.querySelector<HTMLButtonElement>('button.another')!.click();

    await vi.waitFor(() => expect(view.querySelector('.result')).toBeNull());
    expect(view.querySelector('input[type="file"]')).not.toBeNull();
    expect(submit(view).disabled).toBe(true);
  });
});

describe('an upload that the Engine refuses', () => {
  test.each([
    [demoJars().junk, 'The file is not a valid jar.'],
    [demoJars().noPipeline, 'The jar declares no pipeline.'],
    [new Uint8Array(2000).fill(9), 'The file is not a valid jar.'],
  ])('422 says why, in the words of the screen', async (bytes, words) => {
    const view = await page();
    await uploadWith(view, bytes);
    await vi.waitFor(() => expect(alertText(view)).toContain(words));
    expect(view.querySelector('.result')).toBeNull();
  });

  test('413: the file is over the limit', async () => {
    const view = await page();
    app.engine.backend.maxUploadBytes = 10;
    await uploadWith(view, demoJars().slow);
    await vi.waitFor(() =>
      expect(alertText(view)).toContain('The file is larger than the upload limit.'),
    );
    expect(alertText(view)).toContain('413 too_large');
  });

  test.each([
    [
      {
        error: 'core_classes_bundled',
        message: 'The jar contains dev/lawlan/runline/core/Pipeline.class.',
      },
      'The jar contains classes of dev.lawlan.runline.core',
      'dev/lawlan/runline/core/Pipeline.class',
    ],
    [
      { error: 'duplicate_pipeline_name', message: 'Two pipelines are named twin.' },
      'Two pipelines in the jar have the same name.',
      'Two pipelines are named twin.',
    ],
    [
      { error: 'invalid_pipeline_name', message: 'Not allowed: bad name, worse/name.' },
      'A pipeline name in the jar is not allowed.',
      'bad name, worse/name',
    ],
    [
      { error: 'jar_entry_too_large', message: 'entry big/<i>data</i>.bin is too large' },
      'An entry of the jar is too large once unpacked.',
      'big/<i>data</i>.bin',
    ],
    [
      { error: 'jar_too_many_entries', message: 'too many entries' },
      'The jar has too many entries.',
      'too many entries',
    ],
    [
      { error: 'jar_expanded_too_large', message: 'too big unpacked' },
      'The jar is too large once unpacked.',
      'too big unpacked',
    ],
    [
      { error: 'metadata_unreadable', message: 'class com.acme.Broken' },
      'The metadata of a pipeline could not be read.',
      'com.acme.Broken',
    ],
  ])(
    '422 %j: the code in words, and what the Engine names (an entry, a class) as text',
    async (body, words, named) => {
      const view = await page();
      app.engine.backend.nextUploadRefusal = { status: 422, json: body };
      await uploadWith(view, demoJars().slow);

      await vi.waitFor(() => expect(alertText(view)).toContain(words));
      const details = view.querySelector('[role="alert"] details')!;
      expect(details.hasAttribute('open')).toBe(true);
      expect(details.textContent).toContain(named);
      expect(details.querySelector('i')).toBeNull();
    },
  );

  test('says in the language of the screen', async () => {
    const view = await page(['zh-TW']);
    await uploadWith(view, demoJars().junk);
    await vi.waitFor(() => expect(alertText(view)).toContain('不是有效的 jar'));
  });

  test('can try again with another file after a refusal', async () => {
    const view = await page();
    await uploadWith(view, demoJars().junk);
    await vi.waitFor(() => expect(alertText(view)).not.toBe(''));

    choose(view, file(demoJars().slow));
    await vi.waitFor(() => expect(alertText(view)).toBe(''));
    submit(view).click();
    await vi.waitFor(() => expect(view.querySelector('.result')).not.toBeNull());
  });

  test('an Engine that cannot be reached is said as that, and the same file can be sent again', async () => {
    const view = await page();
    app.engine.faults.push({ match: /POST \/api\/v1\/artifacts/, status: 0, drop: true, times: 1 });
    await uploadWith(view, demoJars().slow);
    await vi.waitFor(() => expect(alertText(view)).toContain('could not be reached'));

    expect(submit(view).disabled).toBe(false);
    submit(view).click();
    await vi.waitFor(() => expect(view.querySelector('.result')).not.toBeNull());
  });

  test('a session that ended during the upload is the sign-in: the page shows nothing of it', async () => {
    const view = await page();
    app.engine.callers = [];
    await uploadWith(view, demoJars().slow);
    await vi.waitFor(() =>
      expect(app.session.state).toMatchObject({ status: 'anonymous', reason: 'expired' }),
    );
    expect(view.querySelector('.result')).toBeNull();
  });
});

describe('while it uploads', () => {
  test('shows that it is busy, with a progress bar, and the upload can be cancelled', async () => {
    const view = await page();
    app.engine.mode = 'hang';
    await uploadWith(view, demoJars().slow);

    await vi.waitFor(() => expect(view.querySelector('progress')).not.toBeNull());
    expect(submit(view).disabled).toBe(true);
    expect(view.querySelector('[role="status"]')!.textContent).toContain('Uploading');

    view.querySelector<HTMLButtonElement>('button.cancel')!.click();

    await vi.waitFor(() => expect(view.textContent).toContain('The upload was cancelled.'));
    expect(view.querySelector('progress')).toBeNull();
    expect(alertText(view)).toBe('');
    expect(submit(view).disabled).toBe(false);
    expect(app.engine.backend.artifacts.size).toBe(0);
  });

  test('says how big the whole file is, so that the bar has a length (that it fills as the bytes go is seen in the real browser: npm run e2e)', async () => {
    const view = await page();
    app.engine.mode = 'hang';
    await uploadWith(view, new Uint8Array(300_000).fill(1));

    await vi.waitFor(() =>
      expect(view.querySelector<HTMLProgressElement>('progress')!.max).toBe(300_000),
    );
    expect(view.textContent).toContain('293 KiB');
  });
});
