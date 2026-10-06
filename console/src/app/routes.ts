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
  | 'engine';

export interface AppRoute {
  /** Names the translations of the route (`nav.<id>`, `page.<id>.title`). */
  id: RouteId;
  path: string;
  group: NavGroupId;
  /** Shown to admins only: the navigation hides it from developers, the shell refuses the path. */
  adminOnly: boolean;
}

// The pages of the Console (design: "Runline Console"). The pages themselves come with WI-34 on;
// the paths are the Console's history routes, which the Engine answers with the entry page.
export const ROUTES: readonly AppRoute[] = [
  { id: 'overview', path: '/', group: 'workspace', adminOnly: false },
  { id: 'pipelines', path: '/pipelines', group: 'workspace', adminOnly: false },
  { id: 'runs', path: '/runs', group: 'workspace', adminOnly: false },
  { id: 'upload', path: '/upload', group: 'workspace', adminOnly: false },
  { id: 'triggers', path: '/triggers', group: 'automation', adminOnly: true },
  { id: 'allowlist', path: '/allowlist', group: 'admin', adminOnly: true },
  { id: 'resources', path: '/resources', group: 'admin', adminOnly: true },
  { id: 'engine', path: '/engine', group: 'admin', adminOnly: true },
];

const GROUP_ORDER: readonly NavGroupId[] = ['workspace', 'automation', 'admin'];

export function matchRoute(pathname: string): AppRoute | null {
  const path = pathname.length > 1 ? pathname.replace(/\/$/, '') : pathname;
  return ROUTES.find((route) => route.path === path) ?? null;
}

export interface NavGroup {
  id: NavGroupId;
  /** Every item of the group is for admins: the group carries the Admin mark. */
  adminOnly: boolean;
  routes: AppRoute[];
}

/** The navigation for a role: only what the role may open, and no group left empty. */
export function navGroups(role: Role): NavGroup[] {
  return GROUP_ORDER.map((id) => ({
    id,
    routes: ROUTES.filter((r) => r.group === id && (role === 'admin' || !r.adminOnly)),
  }))
    .filter((group) => group.routes.length > 0)
    .map((group) => ({ ...group, adminOnly: group.routes.every((r) => r.adminOnly) }));
}
