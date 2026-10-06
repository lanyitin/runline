<script lang="ts">
  import type { Snippet } from 'svelte';
  import { useApp } from '../app/context';
  import EngineChip from '../engine/EngineChip.svelte';
  import BrandMark from '../ui/BrandMark.svelte';
  import LanguageSwitcher from '../ui/LanguageSwitcher.svelte';

  // The frame of what is shown before anyone has signed in (the sign-in page of WI-34, and the
  // pages that are not reachable without it). The language switch and the Engine's version and
  // commit hash are here too: they do not depend on who is signed in.
  interface Props {
    children: Snippet;
  }
  let { children }: Props = $props();

  const { i18n } = useApp();
</script>

<a class="skip" href="#main">{i18n.t('app.skipToContent')}</a>
<div class="public">
  <header>
    <div class="brand">
      <BrandMark />
      <span>Runline <span class="console">Console</span></span>
    </div>
    <LanguageSwitcher />
  </header>
  <main id="main" tabindex="-1">
    {@render children()}
  </main>
  <footer>
    <EngineChip placement="footer" />
  </footer>
</div>

<style>
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
  .public {
    display: flex;
    flex-direction: column;
    min-height: 100vh;
    background-color: var(--bg);
    /* The dotted grid of the design system, drawn by CSS: no image to load. */
    background-image: radial-gradient(circle, var(--border-strong) 1px, transparent 1px);
    background-size: 24px 24px;
  }
  header,
  footer {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--space-5) var(--space-6);
  }
  footer {
    justify-content: center;
  }
  .brand {
    display: flex;
    align-items: center;
    gap: var(--space-3);
    font-size: 1.25rem;
    font-weight: 600;
  }
  .console {
    color: var(--text-muted);
    font-weight: 400;
  }
  main {
    flex: 1;
    display: grid;
    place-items: center;
    padding: var(--space-6);
  }
  main:focus {
    outline: none;
  }
</style>
