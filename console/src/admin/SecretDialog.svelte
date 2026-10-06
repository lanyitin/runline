<script lang="ts">
  import { useApp } from '../app/context';
  import CopyButton from '../ui/CopyButton.svelte';
  import Dialog from '../ui/Dialog.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import WebhookUsage from './WebhookUsage.svelte';

  // The secret of a webhook, in the one moment it exists in the Console: the answer that made the
  // trigger or rotated its secret (08-api.md). It is held by this dialog and nowhere else: not in
  // the storage of the tab, not in the address, not in the state of the router, not in a store. When
  // the dialog goes, so does the secret. It is not closed by accident: Escape and the close button
  // are off, and Done waits for the person to say the secret is kept.
  interface Props {
    name: string;
    webhookPath: string;
    secret: string;
    /** The secret is a new one for a trigger that had one. */
    rotated?: boolean;
    onclose: () => void;
  }
  let { name, webhookPath, secret, rotated = false, onclose }: Props = $props();

  const { i18n } = useApp();
  let kept = $state(false);
</script>

<Dialog
  title={i18n.t(rotated ? 'secret.title.rotated' : 'secret.title.created', { name })}
  dismissible={false}
>
  <div class="rl-notice warning" role="status">
    <p class="once">{i18n.t('secret.once')}</p>
    {#if rotated}
      <p class="old">{i18n.t('secret.rotated')}</p>
    {/if}
  </div>

  <div>
    <span class="rl-label">{i18n.t('secret.label')}</span>
    <p class="secret rl-mono"><PlainText value={secret} mono /></p>
    <CopyButton
      text={secret}
      label={i18n.t('secret.copy')}
      copied={i18n.t('common.copied')}
      failed={i18n.t('common.copyFailed')}
    />
  </div>

  <WebhookUsage {webhookPath} {secret} />

  <label class="kept">
    <input type="checkbox" bind:checked={kept} />
    {i18n.t('secret.kept')}
  </label>

  {#snippet footer()}
    <button class="rl-btn primary" type="button" disabled={!kept} onclick={onclose}>
      {i18n.t('secret.done')}
    </button>
  {/snippet}
</Dialog>

<style>
  .once,
  .old {
    margin: 0;
  }
  .old {
    margin-top: var(--space-2);
    font-weight: 600;
  }
  .secret {
    margin: var(--space-2) 0;
    padding: var(--space-3) var(--space-4);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-md);
    background: var(--surface-subtle);
    font-size: var(--text-sm);
    overflow-wrap: anywhere;
    user-select: all;
  }
  .kept {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
</style>
