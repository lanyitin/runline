<script lang="ts">
  import type { Snippet } from 'svelte';
  import { useApp } from '../app/context';
  import type { Identity } from '../app/identity.svelte';
  import Sidebar from './Sidebar.svelte';
  import TopBar from './TopBar.svelte';
  import type { Crumb } from './Breadcrumb.svelte';

  // The frame of every page of a signed-in user: sidebar, top bar, and the page in `main`.
  interface Props {
    identity: Identity;
    crumbs: Crumb[];
    children: Snippet;
  }
  let { identity, crumbs, children }: Props = $props();

  const { i18n } = useApp();
</script>

<a class="skip" href="#main">{i18n.t('app.skipToContent')}</a>
<div class="shell">
  <Sidebar role={identity.role} />
  <div class="column">
    <TopBar {crumbs} {identity} />
    <main id="main" tabindex="-1">
      {@render children()}
    </main>
  </div>
</div>

<style>
  /* Out of sight until the keyboard reaches it: the first stop of the page. */
  .skip {
    position: absolute;
    left: var(--space-3);
    top: -4rem;
    z-index: 100;
    padding: var(--space-2) var(--space-3);
    border-radius: var(--radius-md);
    background: var(--accent-solid);
    color: var(--on-accent);
  }
  .skip:focus {
    top: var(--space-3);
  }
  .shell {
    display: flex;
    min-height: 100vh;
  }
  .column {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
  }
  main {
    flex: 1;
    padding: var(--space-6);
  }
  main:focus {
    outline: none;
  }
</style>
