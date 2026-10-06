<script lang="ts">
  import { useApp } from '../app/context';
  import type { Role } from '../app/identity.svelte';
  import { navGroups } from '../app/routes';
  import EngineChip from '../engine/EngineChip.svelte';
  import BrandMark from '../ui/BrandMark.svelte';
  import Link from '../ui/Link.svelte';

  // The navigation of a signed-in user: only the groups and pages the role may open, the pages of
  // admins marked with a tag in words. The Engine's version stays at the foot.
  interface Props {
    role: Role;
  }
  let { role }: Props = $props();

  const { i18n } = useApp();
  const groups = $derived(navGroups(role));
</script>

<aside class="sidebar">
  <div class="brand">
    <BrandMark />
    <span>Runline</span>
  </div>

  <nav aria-label={i18n.t('nav.label')}>
    {#each groups as group (group.id)}
      <section>
        <h2>{i18n.t(`nav.group.${group.id}`)}</h2>
        <ul>
          {#each group.routes as route (route.id)}
            <li>
              <Link href={route.path} class="item">
                <span class="label">{i18n.t(`nav.${route.id}`)}</span>
                {#if route.adminOnly}
                  <span class="admin-tag">{i18n.t('nav.adminTag')}</span>
                {/if}
              </Link>
            </li>
          {/each}
        </ul>
      </section>
    {/each}
  </nav>

  <div class="foot">
    <EngineChip placement="sidebar" />
  </div>
</aside>

<style>
  .sidebar {
    position: sticky;
    top: 0;
    display: flex;
    flex-direction: column;
    width: var(--sidebar-width);
    height: 100vh;
    flex: none;
    background: var(--surface);
    border-right: 1px solid var(--border);
  }
  .brand {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-5);
    font-size: 1.25rem;
    font-weight: 600;
    letter-spacing: -0.01em;
  }
  nav {
    flex: 1;
    overflow-y: auto;
    padding: 0 var(--space-4);
  }
  section + section {
    margin-top: var(--space-5);
  }
  h2 {
    padding: 0 var(--space-2);
    margin-bottom: var(--space-2);
    color: var(--text-muted);
    font-size: var(--text-2xs);
    font-weight: 600;
    letter-spacing: 0.1em;
    text-transform: uppercase;
  }
  ul {
    margin: 0;
    padding: 0;
    list-style: none;
    display: grid;
    gap: var(--space-1);
  }
  /* The anchor is made by Link, so its class is reached from outside the component. */
  :global(.sidebar a.item) {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    border-radius: var(--radius-md);
    color: var(--text-secondary);
    font-weight: 500;
    transition: background var(--motion);
  }
  :global(.sidebar a.item:hover) {
    background: var(--surface-subtle);
    text-decoration: none;
  }
  /* The page you are on: tinted, and a 1px accent line under it (the words stay the same). */
  :global(.sidebar a.item[aria-current='page']) {
    background: var(--accent-tint);
    color: var(--accent-text);
    box-shadow: inset 0 -1px 0 var(--accent);
  }
  .admin-tag {
    padding: 0 var(--space-1);
    border-radius: 4px;
    background: var(--border);
    color: var(--text-secondary);
    font-size: 0.5625rem;
    font-weight: 600;
    letter-spacing: 0.06em;
    text-transform: uppercase;
  }
  .foot {
    padding: var(--space-4);
    border-top: 1px solid var(--border);
  }
</style>
