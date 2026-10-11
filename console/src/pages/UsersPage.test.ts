import { afterEach, describe, expect, test, vi } from 'vitest';
import { createTestApp, type TestApp } from '../../test-support/app';
import UsersPage from './UsersPage.svelte';

let app: TestApp;
afterEach(() => app.dispose());

const root = { name: 'root', role: 'admin' as const };

const open = async (options: { languages?: string[]; people?: Array<[string, 'developer' | 'admin']> } = {}) => {
  app = await createTestApp({ identity: root, languages: options.languages, oidc: {} });
  for (const [name, role] of options.people ?? [['eli', 'developer'], ['dana', 'admin']]) {
    app.engine.oidc!.signIn({ name, role });
  }
  app.context.router.navigate('/users');
  return app.mount(UsersPage);
};
const rows = (view: HTMLElement) => [...view.querySelectorAll<HTMLElement>('table.users tbody tr')];
const loaded = (view: HTMLElement) => vi.waitFor(() => expect(rows(view).length).toBeGreaterThan(0));
const row = (view: HTMLElement, name: string) =>
  rows(view).find((r) => r.querySelector('.name')!.textContent!.trim() === name)!;
const button = (root: ParentNode, label: string) =>
  [...root.querySelectorAll<HTMLButtonElement>('button')].find((b) => b.textContent!.trim() === label)!;
const dialog = (view: HTMLElement) => view.querySelector<HTMLElement>('[role="dialog"]');
const text = (el: Element | null | undefined) => el?.textContent?.replace(/\s+/g, ' ').trim() ?? '';

describe('the users', () => {
  test('are listed with their name, display name, state, last sign-in and number of sessions', async () => {
    const view = await open();
    await loaded(view);

    expect(view.querySelector('h1')!.textContent).toBe('Users');
    expect(rows(view).map((r) => r.querySelector('.name')!.textContent!.trim())).toEqual(['dana', 'eli']);
    const eli = row(view, 'eli');
    expect(text(eli.querySelector('.display'))).toBe('First eli');
    expect(text(eli.querySelector('.status'))).toBe('Active');
    expect(eli.querySelector('.last time')).not.toBeNull();
    expect(text(eli.querySelector('.count'))).toBe('1');
  });

  test('say so when nobody has signed in yet', async () => {
    const view = await open({ people: [] });

    await vi.waitFor(() => expect(view.querySelector('.rl-empty')).not.toBeNull());
    expect(text(view.querySelector('.rl-empty'))).toContain('Nobody has signed in yet');
    expect(view.querySelector('table.users')).toBeNull();
  });

  test('are shown as plain text, whatever the provider sent as their names', async () => {
    const hostile = '<img src=x onerror="document.title=1">';
    const view = await open({ people: [[hostile, 'developer']] });
    await loaded(view);

    expect(view.querySelector('img')).toBeNull();
    expect(row(view, hostile).querySelector('.name')!.textContent).toContain(hostile);
    expect(text(row(view, hostile).querySelector('.display'))).toBe(`First ${hostile}`);
    button(row(view, hostile), 'Sessions').click();
    await vi.waitFor(() => expect(dialog(view)).not.toBeNull());
    expect(dialog(view)!.querySelector('h2')!.textContent).toContain(hostile);
    expect(view.querySelector('img')).toBeNull();
  });

  test('are in Chinese when the language is Chinese', async () => {
    const view = await open({ languages: ['zh-TW'] });
    await loaded(view);

    expect(view.querySelector('h1')!.textContent).toBe('Users 使用者');
    expect(text(row(view, 'eli').querySelector('.status'))).toBe('啟用中');
    expect(button(row(view, 'eli'), '停用')).toBeDefined();
  });
});

describe('disabling a user', () => {
  test('asks first, and says that it ends every session at once and refuses the sign-in', async () => {
    const view = await open();
    await loaded(view);

    button(row(view, 'eli'), 'Disable').click();
    await vi.waitFor(() => expect(dialog(view)).not.toBeNull());

    expect(text(dialog(view)!.querySelector('h2'))).toBe('Disable eli?');
    expect(text(dialog(view)!)).toContain('every session at once');
    expect(text(dialog(view)!)).toContain('cannot sign in again until you enable');
    expect(app.engine.received.some((r) => r.path.endsWith('/disable'))).toBe(false);

    button(dialog(view)!, 'Cancel').click();
    await vi.waitFor(() => expect(dialog(view)).toBeNull());
    expect(app.engine.oidc!.user('eli')!.disabled).toBe(false);
  });

  test('does it on confirming: the row says disabled and by whom, has no sessions, and the result is told', async () => {
    const view = await open();
    await loaded(view);
    const cookie = app.engine.oidc!.signIn({ name: 'eli', role: 'developer' });

    button(row(view, 'eli'), 'Disable').click();
    await vi.waitFor(() => expect(dialog(view)).not.toBeNull());
    button(dialog(view)!, 'Disable and end sessions').click();

    await vi.waitFor(() => expect(dialog(view)).toBeNull());
    await vi.waitFor(() => expect(text(row(view, 'eli').querySelector('.status'))).toContain('Disabled'));
    expect(text(row(view, 'eli').querySelector('.status'))).toContain('root');
    expect(text(row(view, 'eli').querySelector('.count'))).toBe('0');
    expect(app.engine.oidc!.has(cookie)).toBe(false);
    expect(text(view.querySelector('[data-result]'))).toBe('Disabled eli. 2 sessions ended.');
    expect(button(row(view, 'eli'), 'Enable')).toBeDefined();
  });

  test('warns the administrator who disables themselves', async () => {
    const view = await open({ people: [['root', 'admin']] });
    await loaded(view);

    button(row(view, 'root'), 'Disable').click();
    await vi.waitFor(() => expect(dialog(view)).not.toBeNull());

    expect(text(dialog(view)!)).toContain('your own account');
  });

  test('says in words what the Engine refused, and the dialog stays: a user who is gone (404)', async () => {
    const view = await open();
    await loaded(view);
    app.engine.faults.push({
      match: /POST \/api\/v1\/users\/eli\/disable/,
      status: 404,
      body: { error: 'user_not_found', message: 'No such user.' },
      times: 1,
    });

    button(row(view, 'eli'), 'Disable').click();
    await vi.waitFor(() => expect(dialog(view)).not.toBeNull());
    button(dialog(view)!, 'Disable and end sessions').click();

    await vi.waitFor(() => expect(text(dialog(view)!.querySelector('[role="alert"]'))).toContain('That user does not exist.'));
    expect(app.engine.oidc!.user('eli')!.disabled).toBe(false);
  });

  test.each([
    [403, 'forbidden', 'Your role is not allowed to do this.'],
    [403, 'csrf_invalid', 'security token is missing or wrong'],
    [403, 'origin_invalid', 'did not come from this site'],
    [409, 'not_an_oidc_user', 'belongs to an API token'],
  ])('says in words what the Engine refused: %i %s', async (status, code, words) => {
    const view = await open();
    await loaded(view);
    app.engine.faults.push({ match: /POST \/api\/v1\/users\/eli\/disable/, status, body: { error: code, message: 'x' }, times: 1 });

    button(row(view, 'eli'), 'Disable').click();
    await vi.waitFor(() => expect(dialog(view)).not.toBeNull());
    button(dialog(view)!, 'Disable and end sessions').click();

    await vi.waitFor(() => expect(text(dialog(view)!.querySelector('[role="alert"]'))).toContain(words));
  });
});

describe('enabling a user', () => {
  test('asks first, and says that the ended sessions do not come back; then the row is active again', async () => {
    const view = await open();
    await loaded(view);
    app.engine.oidc!.setDisabled('eli', true, 'root');
    button(view, 'Refresh').click();
    await vi.waitFor(() => expect(text(row(view, 'eli').querySelector('.status'))).toContain('Disabled'));

    button(row(view, 'eli'), 'Enable').click();
    await vi.waitFor(() => expect(dialog(view)).not.toBeNull());
    expect(text(dialog(view)!)).toContain('do not come back');
    button(dialog(view)!, 'Enable').click();

    await vi.waitFor(() => expect(dialog(view)).toBeNull());
    await vi.waitFor(() => expect(text(row(view, 'eli').querySelector('.status'))).toBe('Active'));
    expect(text(view.querySelector('[data-result]'))).toBe('Enabled eli.');
  });
});

describe('the sessions of a user', () => {
  test('are listed with when they began and were last used, and one is ended without disabling the user', async () => {
    const view = await open();
    await loaded(view);
    app.engine.oidc!.signIn({ name: 'eli', role: 'developer' });

    button(row(view, 'eli'), 'Sessions').click();
    await vi.waitFor(() => expect(dialog(view)!.querySelectorAll('tbody tr')).toHaveLength(2));
    expect(text(dialog(view)!.querySelector('h2'))).toBe('Sessions of eli');
    expect(dialog(view)!.querySelectorAll('tbody tr time')).toHaveLength(4);
    expect(dialog(view)!.innerHTML).not.toContain('runline_session');

    button(dialog(view)!.querySelector<HTMLElement>('tbody tr')!, 'End').click();

    await vi.waitFor(() => expect(dialog(view)!.querySelectorAll('tbody tr')).toHaveLength(1));
    expect(app.engine.oidc!.user('eli')!.disabled).toBe(false);
    await vi.waitFor(() => expect(text(row(view, 'eli').querySelector('.count'))).toBe('1'));
  });

  test('are all ended after a question, and the dialog says there are none', async () => {
    const view = await open();
    await loaded(view);
    app.engine.oidc!.signIn({ name: 'eli', role: 'developer' });

    button(row(view, 'eli'), 'Sessions').click();
    await vi.waitFor(() => expect(dialog(view)!.querySelectorAll('tbody tr')).toHaveLength(2));
    button(dialog(view)!, 'End all sessions').click();
    await vi.waitFor(() => expect(text(dialog(view)!)).toContain('End all sessions of eli?'));
    expect(app.engine.oidc!.sessionsOf('eli')).toHaveLength(2);
    button(dialog(view)!, 'End all sessions').click();

    await vi.waitFor(() => expect(text(dialog(view)!)).toContain('No valid sessions.'));
    expect(app.engine.oidc!.sessionsOf('eli')).toHaveLength(0);
    await vi.waitFor(() => expect(text(row(view, 'eli').querySelector('.count'))).toBe('0'));
  });

  test('say in words when a session is gone already (404 session_not_found)', async () => {
    const view = await open();
    await loaded(view);
    button(row(view, 'eli'), 'Sessions').click();
    await vi.waitFor(() => expect(dialog(view)!.querySelectorAll('tbody tr')).toHaveLength(1));
    app.engine.faults.push({
      match: /DELETE \/api\/v1\/users\/eli\/sessions\//,
      status: 404,
      body: { error: 'session_not_found', message: 'x' },
      times: 1,
    });

    button(dialog(view)!, 'End').click();

    await vi.waitFor(() => expect(text(dialog(view)!.querySelector('[role="alert"]'))).toContain('That session no longer exists.'));
  });
});

describe('a failure to read the users', () => {
  test('is said in words, with a way to try again', async () => {
    app = await createTestApp({ identity: root, oidc: {} });
    app.engine.faults.push({ match: /GET \/api\/v1\/users$/, status: 403, body: { error: 'forbidden', message: 'x' }, times: 1 });
    app.context.router.navigate('/users');
    const view = app.mount(UsersPage);

    await vi.waitFor(() => expect(text(view.querySelector('[role="alert"]'))).toContain('Your role is not allowed to do this.'));
    app.engine.oidc!.signIn({ name: 'eli', role: 'developer' });
    button(view, 'Retry').click();
    await loaded(view);
  });
});
