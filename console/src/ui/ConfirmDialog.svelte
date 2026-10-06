<script lang="ts">
  import type { Snippet } from 'svelte';
  import type { ApiFailure } from '../api/failure';
  import { useApp } from '../app/context';
  import ApiErrorNotice from './ApiErrorNotice.svelte';
  import Dialog from './Dialog.svelte';

  // A question that has to be answered before something is done (ADR-015: what is destructive or
  // reaches far is confirmed). The body says what it will do and what follows from it. The keyboard
  // starts on Cancel, so that Enter never does what cannot be undone. While the answer is being
  // carried out it cannot be given again or left; what the Engine refused is said inside the
  // dialog, which stays open, so that the person sees it where they are.
  interface Props {
    title: string;
    confirmLabel: string;
    /** The act is destructive or reaches far: its button looks like it. */
    danger?: boolean;
    busy?: boolean;
    failure?: ApiFailure | null;
    onconfirm: () => void;
    oncancel: () => void;
    children: Snippet;
  }
  let {
    title,
    confirmLabel,
    danger = false,
    busy = false,
    failure = null,
    onconfirm,
    oncancel,
    children,
  }: Props = $props();

  const { i18n } = useApp();
</script>

<Dialog {title} dismissible={!busy} onclose={oncancel}>
  {@render children()}
  {#if failure}
    <ApiErrorNotice {failure} />
  {/if}
  {#snippet footer()}
    <button class="rl-btn" type="button" data-autofocus disabled={busy} onclick={oncancel}>
      {i18n.t('common.cancel')}
    </button>
    <button
      class="rl-btn confirm {danger ? 'danger solid' : 'primary'}"
      type="button"
      disabled={busy}
      onclick={onconfirm}
    >
      {confirmLabel}
    </button>
  {/snippet}
</Dialog>
