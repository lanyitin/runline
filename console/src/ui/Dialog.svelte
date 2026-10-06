<script lang="ts">
  import type { Snippet } from 'svelte';
  import { onMount } from 'svelte';
  import { useApp } from '../app/context';

  // A modal dialog: it is labelled by its title, takes the keyboard when it opens, keeps it inside
  // while it is open (Tab goes round), and gives it back to where it was when it closes. Escape and
  // the close button close it, unless it is not `dismissible`: a dialog that has to be answered with
  // one of its own buttons (the secret of a webhook is shown once and must be copied). The page
  // behind cannot scroll. The first thing marked `data-autofocus` is the one that gets the keyboard;
  // without one, the first thing that can be used.
  interface Props {
    title: string;
    dismissible?: boolean;
    onclose?: () => void;
    children: Snippet;
    footer?: Snippet;
  }
  let { title, dismissible = true, onclose, children, footer }: Props = $props();

  const { i18n } = useApp();
  const titleId = $props.id();
  let dialog = $state<HTMLElement | null>(null);

  // Where the keyboard was before the dialog: it is given back when the dialog goes.
  const before = document.activeElement instanceof HTMLElement ? document.activeElement : null;

  const FOCUSABLE =
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';
  const focusable = () => [...(dialog?.querySelectorAll<HTMLElement>(FOCUSABLE) ?? [])];

  onMount(() => {
    document.body.classList.add('rl-modal-open');
    const wanted = dialog?.querySelector<HTMLElement>('[data-autofocus]') ?? focusable()[0] ?? dialog;
    wanted?.focus();
    return () => {
      document.body.classList.remove('rl-modal-open');
      if (before?.isConnected) before.focus();
    };
  });

  function keydown(event: KeyboardEvent) {
    if (event.key === 'Escape' && dismissible) {
      event.preventDefault();
      event.stopPropagation();
      onclose?.();
      return;
    }
    if (event.key !== 'Tab') return;
    const items = focusable();
    if (items.length === 0) {
      event.preventDefault();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const active = document.activeElement;
    if (event.shiftKey && (active === first || active === dialog)) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    }
  }
</script>

<div class="overlay">
  <div
    bind:this={dialog}
    class="dialog"
    role="dialog"
    aria-modal="true"
    aria-labelledby={titleId}
    tabindex="-1"
    onkeydown={keydown}
  >
    <header>
      <h2 id={titleId}>{title}</h2>
      {#if dismissible}
        <button class="close" type="button" aria-label={i18n.t('common.close')} onclick={() => onclose?.()}>
          ×
        </button>
      {/if}
    </header>
    <div class="body">
      {@render children()}
    </div>
    {#if footer}
      <footer>
        {@render footer()}
      </footer>
    {/if}
  </div>
</div>

<style>
  .overlay {
    position: fixed;
    inset: 0;
    z-index: 50;
    display: grid;
    place-items: center;
    padding: var(--space-5);
    background: var(--overlay);
  }
  .dialog {
    display: flex;
    flex-direction: column;
    width: min(40rem, 100%);
    max-height: calc(100vh - 2 * var(--space-5));
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background: var(--surface);
    box-shadow: var(--shadow-popover);
  }
  .dialog:focus {
    outline: none;
  }
  header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: var(--space-3);
    padding: var(--space-4) var(--space-5);
    border-bottom: 1px solid var(--border);
  }
  h2 {
    font-size: var(--text-md);
    font-weight: 600;
  }
  .close {
    padding: 0 var(--space-2);
    border: 0;
    background: none;
    color: var(--text-secondary);
    font: inherit;
    font-size: var(--text-xl);
    line-height: 1;
    cursor: pointer;
  }
  .body {
    display: grid;
    gap: var(--space-4);
    padding: var(--space-5);
    overflow-y: auto;
  }
  footer {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: var(--space-2);
    padding: var(--space-4) var(--space-5);
    border-top: 1px solid var(--border);
  }
</style>
