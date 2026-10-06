<script module lang="ts">
  export interface Crumb {
    label: string;
    /** The last crumb is the page itself and is not a link. */
    href?: string;
  }
</script>

<script lang="ts">
  import { useApp } from '../app/context';
  import Link from '../ui/Link.svelte';

  interface Props {
    crumbs: Crumb[];
  }
  let { crumbs }: Props = $props();

  const { i18n } = useApp();
</script>

<nav aria-label={i18n.t('topbar.breadcrumb')}>
  <ol>
    {#each crumbs as crumb, index (index)}
      <li>
        {#if index > 0}<span class="sep" aria-hidden="true">›</span>{/if}
        {#if index === crumbs.length - 1}
          <span aria-current="page" class="current">{crumb.label}</span>
        {:else if crumb.href}
          <Link href={crumb.href}>{crumb.label}</Link>
        {:else}
          <span>{crumb.label}</span>
        {/if}
      </li>
    {/each}
  </ol>
</nav>

<style>
  ol {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin: 0;
    padding: 0;
    list-style: none;
    color: var(--text-muted);
  }
  li {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  .sep {
    color: var(--border-strong);
  }
  .current {
    color: var(--text);
    font-weight: 500;
  }
</style>
