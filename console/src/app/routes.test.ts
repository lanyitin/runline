import { describe, expect, test } from 'vitest';
import { matchRoute, navGroups, ROUTES } from './routes';

describe('matchRoute', () => {
  test.each([
    ['/', 'overview'],
    ['/pipelines', 'pipelines'],
    ['/runs', 'runs'],
    ['/upload', 'upload'],
    ['/triggers', 'triggers'],
    ['/allowlist', 'allowlist'],
    ['/resources', 'resources'],
    ['/engine', 'engine'],
  ])('%s is %s', (path, id) => expect(matchRoute(path)?.id).toBe(id));

  test('a trailing slash does not matter', () => {
    expect(matchRoute('/runs/')?.id).toBe('runs');
  });

  test.each([
    ['/runs/new', 'runNew', {}],
    ['/runs/0b8a1c1e-6c5e-4d3a-9d61-111111111111', 'run', { runId: '0b8a1c1e-6c5e-4d3a-9d61-111111111111' }],
    ['/pipelines/abc123', 'pipeline', { contentHash: 'abc123' }],
  ])('the page of one thing, %s, is %s with its parameters', (path, id, params) => {
    expect(matchRoute(path)).toMatchObject({ id, params });
  });

  test('the pages of one thing are not in the navigation, and belong to the group of their list', () => {
    expect(matchRoute('/runs/new')).toMatchObject({ nav: false, group: 'workspace' });
    expect(matchRoute('/runs/some-id')).toMatchObject({ nav: false, group: 'workspace' });
    expect(matchRoute('/pipelines')).toMatchObject({ nav: true });
  });

  test('the page of one thing says which list it belongs to', () => {
    expect(matchRoute('/runs/some-id')).toMatchObject({ parent: 'runs' });
    expect(matchRoute('/runs/new')).toMatchObject({ parent: 'runs' });
    expect(matchRoute('/pipelines/abc')).toMatchObject({ parent: 'pipelines' });
    expect(matchRoute('/runs')).toMatchObject({ parent: null });
  });

  test('a parameter is one segment: no slash, and the escapes of the address are undone', () => {
    expect(matchRoute('/runs/a%20b')).toMatchObject({ params: { runId: 'a b' } });
    expect(matchRoute('/runs/a/b')).toBeNull();
  });

  test.each(['/nothing', '/api/v1/info', '/Runs'])(
    '%s is no route',
    (path) => expect(matchRoute(path)).toBeNull(),
  );
});

describe('the pages of one trigger', () => {
  test.each([
    ['/triggers/new', 'triggerNew'],
    ['/triggers/detail', 'trigger'],
    ['/triggers/edit', 'triggerEdit'],
  ])('%s is %s: for admins only, not in the navigation, under the triggers', (path, id) => {
    expect(matchRoute(path)).toMatchObject({
      id,
      adminOnly: true,
      nav: false,
      group: 'automation',
      parent: 'triggers',
    });
  });

  test('the name of a trigger is in the query, not in the path: a name may have a dot, which the Engine would take for a file', () => {
    expect(matchRoute('/triggers/hook.one')).toBeNull();
  });
});

describe('navGroups', () => {
  test('a developer sees the workspace, the Engine page included, and no admin item', () => {
    const groups = navGroups('developer');
    expect(groups.map((g) => g.id)).toEqual(['workspace']);
    expect(groups[0].routes.map((r) => r.id)).toEqual([
      'overview',
      'pipelines',
      'runs',
      'upload',
      'engine',
    ]);
  });

  test('the Engine page is open to developers', () => {
    expect(matchRoute('/engine')).toMatchObject({ group: 'workspace', adminOnly: false });
  });

  test('an admin sees every group, the admin items marked', () => {
    const groups = navGroups('admin');
    expect(groups.map((g) => g.id)).toEqual(['workspace', 'automation', 'admin']);
    expect(groups.find((g) => g.id === 'admin')!.routes.map((r) => r.id)).toEqual([
      'allowlist',
      'resources',
    ]);
    expect(groups.find((g) => g.id === 'admin')!.adminOnly).toBe(true);
  });

  test('every route is in the navigation of an admin exactly once', () => {
    const shown = navGroups('admin').flatMap((g) => g.routes.map((r) => r.id));
    expect(shown.sort()).toEqual(ROUTES.filter((r) => r.nav).map((r) => r.id).sort());
  });
});
