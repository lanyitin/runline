import { afterEach, expect, test } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import { ApiFailure } from '../api/failure';
import ApiErrorNotice from './ApiErrorNotice.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const show = async (failure: ApiFailure, languages = ['en'], extra: object = {}) => {
  app = await createTestApp({ languages });
  return app
    .mount(ApiErrorNotice, { failure, ...extra })
    .querySelector<HTMLElement>('[role="alert"]')!;
};

test('says the error in the words of the screen, from its code, and not in the words of the Engine', async () => {
  const view = await show(
    new ApiFailure(409, { error: 'unsafe_not_allowed', message: 'pipeline「x」被判定為 unsafe' }),
  );
  expect(view.querySelector('.headline')!.textContent).toContain('UNSAFE');
  expect(view.querySelector('.headline')!.textContent).not.toContain('被判定');
  expect(view.querySelector('details')!.hasAttribute('open')).toBe(false);
});

test('follows the language', async () => {
  const view = await show(new ApiFailure(409, { error: 'unsafe_not_allowed', message: 'x' }), [
    'zh-TW',
  ]);
  expect(view.querySelector('.headline')!.textContent).toContain('unsafe');
  expect(view.querySelector('.headline')!.textContent).toMatch(/管理員/);
});

test('lists the problems, one line each, with the name of the parameter or resource', async () => {
  const view = await show(
    new ApiFailure(422, {
      error: 'invalid_parameters',
      message: 'm',
      problems: [
        { name: 'steps', problem: 'missing' },
        { name: 'nope', problem: 'undeclared' },
      ],
    }),
  );
  expect([...view.querySelectorAll('.problems li')].map((li) => li.textContent)).toEqual([
    'steps: required, but missing.',
    'nope: not declared by the pipeline.',
  ]);
});

test('can leave the problems to the page, which puts them beside the fields', async () => {
  const view = await show(
    new ApiFailure(422, {
      error: 'invalid_parameters',
      message: 'm',
      problems: [{ name: 'a', problem: 'missing' }],
    }),
    ['en'],
    { leaveOutProblems: true },
  );
  expect(view.querySelector('.problems')).toBeNull();
});

test('has the words of the Engine in the details, as text and never as markup, open when asked', async () => {
  const view = await show(
    new ApiFailure(422, {
      error: 'jar_entry_too_large',
      message: 'entry <img src=x onerror=alert(1)> is too large',
    }),
    ['en'],
    { expandServerMessage: true },
  );
  const details = view.querySelector('details')!;
  expect(details.hasAttribute('open')).toBe(true);
  expect(details.textContent).toContain('entry <img src=x onerror=alert(1)> is too large');
  expect(details.querySelector('img')).toBeNull();
  expect(details.textContent).toContain('422 jar_entry_too_large');
});

test('gives the error id of an internal error, to be said when reporting it', async () => {
  const view = await show(
    new ApiFailure(500, { error: 'internal_error', message: 'boom', errorId: 'e-77' }),
  );
  expect(view.querySelector('details')!.textContent).toContain('e-77');
});

test('an answer that did not come is said as that, with no code to show', async () => {
  const view = await show(new ApiFailure(0, null, 'the Engine did not answer'));
  expect(view.querySelector('.headline')!.textContent).toContain('could not be reached');
  expect(view.querySelector('details')).toBeNull();
});

test('a code the Console has no text for is said in general words, with the code in the details', async () => {
  const view = await show(new ApiFailure(418, { error: 'teapot', message: 'short and stout' }));
  expect(view.querySelector('.headline')!.textContent).toContain('teapot');
  expect(view.querySelector('details')!.textContent).toContain('short and stout');
});
