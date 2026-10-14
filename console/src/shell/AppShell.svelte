<script lang="ts">
  import { flushSync, untrack, type Snippet } from 'svelte';
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

  const { i18n, router } = useApp();

  // On a narrow screen (styles/responsive.css) the sidebar is a drawer that the menu button of the
  // top bar opens. It takes the keyboard like a dialog does (Dialog.svelte): the keyboard goes into
  // it, stays in it, Escape closes it, and the menu button gets the keyboard back.
  const drawerId = $props.id();
  let navOpen = $state(false);
  let menuButton = $state<HTMLButtonElement | null>(null);
  let drawer = $state<HTMLElement | null>(null);

  const FOCUSABLE = 'a[href], button:not([disabled])';
  const focusable = () => [...(drawer?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [])];

  function toggle() {
    if (navOpen) return close();
    navOpen = true;
    document.body.classList.add('rl-nav-open');
    flushSync();
    focusable()[0]?.focus();
  }

  function close(restoreFocus = true) {
    if (!navOpen) return;
    navOpen = false;
    document.body.classList.remove('rl-nav-open');
    if (restoreFocus) menuButton?.focus();
  }

  // The page behind the drawer moved on (a link, or the browser's back button): the drawer goes.
  $effect(() => {
    void router.path;
    untrack(() => close(false));
  });
  $effect(() => () => document.body.classList.remove('rl-nav-open'));

  function keydown(event: KeyboardEvent) {
    if (!navOpen) return;
    if (event.key === 'Escape') {
      event.preventDefault();
      close();
      return;
    }
    if (event.key !== 'Tab') return;
    const items = focusable();
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    if (!drawer?.contains(active)) {
      event.preventDefault();
      first?.focus();
    } else if (event.shiftKey && active === first) {
      event.preventDefault();
      last?.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first?.focus();
    }
  }

  // Choosing the page you are on does not move the router, so the drawer is closed by the choice.
  function click(event: MouseEvent) {
    if (navOpen && event.target instanceof Element && drawer?.contains(event.target) && event.target.closest('a[href]')) {
      close();
    }
  }
</script>

<svelte:window onkeydown={keydown} onclick={click} />

<a class="skip" href="#main">{i18n.t('app.skipToContent')}</a>
<div class="shell">
  <Sidebar role={identity.role} id={drawerId} open={navOpen} bind:element={drawer} />
  {#if navOpen}
    <button class="rl-nav-scrim" type="button" tabindex="-1" aria-label={i18n.t('nav.close')} onclick={() => close()}></button>
  {/if}
  <div class="column">
    <TopBar {crumbs} {identity} menuControls={drawerId} menuOpen={navOpen} ontoggle={toggle} bind:menuButton />
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
