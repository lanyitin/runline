import type { Role } from './identity.svelte';

export type NavGroupId = 'workspace' | 'automation' | 'admin';

export type RouteId =
  | 'overview'
  | 'pipelines'
  | 'runs'
  | 'upload'
  | 'triggers'
  | 'allowlist'
  | 'resources'
  | 'engine'
  | 'pipeline'
  | 'runNew'
  | 'run';

/** The pages that the navigation lists: they have a name of their own there (`nav.<id>`). */
export type NavRouteId = Exclude<RouteId, 'pipeline' | 'runNew' | 'run'>;

export interface AppRoute {
  /** Names the translations of the route (`nav.<id>` for those of the navigation, `page.<id>.title`). */
  id: RouteId;
  path: string;
  group: NavGroupId;
  /** Shown to admins only: the navigation hides it from developers, the shell refuses the path. */
  adminOnly: boolean;
  /**
   * Whether the navigation lists it. The page of one thing (a pipeline, a run) is reached from its
   * list, not from the navigation, and belongs to the group of the list.
   */
  nav: boolean;
  /** The list a page of one thing belongs to: its place in the breadcrumb. */
  parent: RouteId | null;
}

/** A route as the address matched it: what the `:name` segments of its path were. */
export interface MatchedRoute extends AppRoute {
  params: Record<string, string>;
}

// The pages of the Console (design: "Runline Console"). The paths are the Console's history routes,
// which the Engine answers with the entry page. A path that looks like a file (it has an extension)
// would not get the entry page, so a name that pipelines may put a dot in (a pipeline name) is
// never a segment of a path: it is in the query.
export const ROUTES: readonly AppRoute[] = [
  { id: 'overview', path: '/', group: 'workspace', adminOnly: false, nav: true, parent: null },
  { id: 'pipelines', path: '/pipelines', group: 'workspace', adminOnly: false, nav: true, parent: null },
  { id: 'runs', path: '/runs', group: 'workspace', adminOnly: false, nav: true, parent: null },
  { id: 'upload', path: '/upload', group: 'workspace', adminOnly: false, nav: true, parent: null },
  // Open to developers: GET /api/v1/system, its data, is for developers and above.
  { id: 'engine', path: '/engine', group: 'workspace', adminOnly: false, nav: true, parent: null },
  { id: 'triggers', path: '/triggers', group: 'automation', adminOnly: true, nav: true, parent: null },
  { id: 'allowlist', path: '/allowlist', group: 'admin', adminOnly: true, nav: true, parent: null },
  { id: 'resources', path: '/resources', group: 'admin', adminOnly: true, nav: true, parent: null },
  {
    id: 'pipeline',
    path: '/pipelines/:contentHash',
    group: 'workspace',
    adminOnly: false,
    nav: false,
    parent: 'pipelines',
  },
  // Before `run`: "new" is no run id.
  { id: 'runNew', path: '/runs/new', group: 'workspace', adminOnly: false, nav: false, parent: 'runs' },
  { id: 'run', path: '/runs/:runId', group: 'workspace', adminOnly: false, nav: false, parent: 'runs' },
];

const GROUP_ORDER: readonly NavGroupId[] = ['workspace', 'automation', 'admin'];

function matchPath(pattern: string, path: string): Record<string, string> | null {
  const wanted = pattern.split('/');
  const got = path.split('/');
  if (wanted.length !== got.length) return null;
  const params: Record<string, string> = {};
  for (let i = 0; i < wanted.length; i += 1) {
    if (wanted[i].startsWith(':')) {
      try {
        params[wanted[i].slice(1)] = decodeURIComponent(got[i]);
      } catch {
        return null;
      }
    } else if (wanted[i] !== got[i]) {
      return null;
    }
  }
  return params;
}

export function matchRoute(pathname: string): MatchedRoute | null {
  const path = pathname.length > 1 ? pathname.replace(/\/$/, '') : pathname;
  for (const route of ROUTES) {
    const params = matchPath(route.path, path);
    if (params) return { ...route, params };
  }
  return null;
}

export type NavRoute = AppRoute & { id: NavRouteId };

export const isNavRoute = (route: AppRoute): route is NavRoute => route.nav;

export interface NavGroup {
  id: NavGroupId;
  /** Every item of the group is for admins: the group carries the Admin mark. */
  adminOnly: boolean;
  routes: NavRoute[];
}

/** The navigation for a role: only what the role may open, and no group left empty. */
export function navGroups(role: Role): NavGroup[] {
  return GROUP_ORDER.map((id) => ({
    id,
    routes: ROUTES.filter(isNavRoute).filter(
      (r) => r.group === id && (role === 'admin' || !r.adminOnly),
    ),
  }))
    .filter((group) => group.routes.length > 0)
    .map((group) => ({ ...group, adminOnly: group.routes.every((r) => r.adminOnly) }));
}
