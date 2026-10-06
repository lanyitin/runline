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

  test.each(['/nothing', '/runs/unknown/deeper', '/api/v1/info', '/Runs'])(
    '%s is no route',
    (path) => expect(matchRoute(path)).toBeNull(),
  );
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
    expect(shown.sort()).toEqual(ROUTES.map((r) => r.id).sort());
  });
});
