<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { RemovalPreview } from '../api/admin-model';
  import { useApp } from '../app/context';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Dialog from '../ui/Dialog.svelte';

  // Deletes a shared resource (`DELETE /api/v1/resources/{name}`), after showing what it would touch
  // (`preview=true`, which changes nothing): the pipeline definitions that declare it and the
  // triggers bound to them, which keep their declaration and are refused new runs, and whether a
  // run holds it or waits for it. A resource in use is not deleted, by the Engine: the dialog says
  // so, before (from the preview) and after (a run may take it in between, 409 `resource_in_use`),
  // and says how to free it: disable it so that the waiting runs fail, or release the holders.
  interface Props {
    name: string;
    /** Done: true when the resource was deleted, false when the dialog was left. */
    onfinished: (deleted: boolean) => void;
  }
  let { name, onfinished }: Props = $props();

  const { i18n, api } = useApp();
  let preview = $state<RemovalPreview | null>(null);
  /** Who uses it, as the Engine said when it refused; null while it is not known to be in use. */
  let inUse = $state<{ holders: number; waiters: number } | null>(null);
  let failure = $state.raw<ApiFailure | null>(null);
  let busy = $state(false);

  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

  $effect(() => {
    api
      .deleteResource(name, { preview: true })
      .then((answered) => {
        preview = answered;
        if (answered?.inUse) inUse = { holders: answered.holders, waiters: answered.waiters };
      })
      .catch((error) => (failure = asFailure(error)));
  });

  async function remove() {
    if (busy || inUse !== null) return;
    busy = true;
    failure = null;
    try {
      await api.deleteResource(name);
      onfinished(true);
    } catch (error) {
      const refused = asFailure(error);
      const body = refused.body as { error?: string; holders?: number; waiters?: number } | null;
      if (body?.error === 'resource_in_use') {
        inUse = { holders: body.holders ?? 0, waiters: body.waiters ?? 0 };
      } else {
        failure = refused;
      }
    } finally {
      busy = false;
    }
  }
</script>

<Dialog title={i18n.t('resources.delete.title', { name })} dismissible={!busy} onclose={() => onfinished(false)}>
  {#if preview === null && failure === null}
    <p class="rl-help" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
  {/if}
  {#if preview}
    <div class="preview">
      <p>
        {i18n.t('resources.delete.declared', { definitions: preview.definitions, triggers: preview.triggers })}
      </p>
      <p>
        {preview.holders + preview.waiters === 0
          ? i18n.t('resources.delete.free')
          : i18n.t('resources.delete.used', { holders: preview.holders, waiters: preview.waiters })}
      </p>
    </div>
    <p>{i18n.t('resources.delete.body')}</p>
  {/if}
  {#if inUse}
    <div class="in-use rl-notice warning" role="alert">
      <p>{i18n.t('resources.delete.inUse', { holders: inUse.holders, waiters: inUse.waiters })}</p>
      <p>{i18n.t('resources.delete.inUse.how')}</p>
    </div>
  {/if}
  {#if failure}
    <ApiErrorNotice {failure} />
  {/if}

  {#snippet footer()}
    <button class="rl-btn" type="button" data-autofocus disabled={busy} onclick={() => onfinished(false)}>
      {i18n.t('common.cancel')}
    </button>
    <button
      class="rl-btn confirm danger solid"
      type="button"
      disabled={busy || preview === null || inUse !== null}
      onclick={() => void remove()}
    >
      {i18n.t('resources.delete.confirm')}
    </button>
  {/snippet}
</Dialog>

<style>
  .preview {
    display: grid;
    gap: var(--space-1);
    font-weight: 600;
  }
  p {
    margin: 0 0 var(--space-2);
  }
  .in-use p:last-child {
    margin-bottom: 0;
  }
</style>
