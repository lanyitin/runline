<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { Holder, Resource, ResourceTypeCatalog } from '../api/admin-model';
  import { createPolled } from '../api/polled.svelte';
  import ResourceCard from '../admin/ResourceCard.svelte';
  import ResourceDeleteDialog from '../admin/ResourceDeleteDialog.svelte';
  import ResourceFormDialog from '../admin/ResourceFormDialog.svelte';
  import SecretsPanel from '../admin/SecretsPanel.svelte';
  import { useApp } from '../app/context';
  import { browserClock, pageVisibility, type Clock, type Visibility } from '../runs/run-watch.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import ConfirmDialog from '../ui/ConfirmDialog.svelte';

  // The shared resources (`GET /api/v1/resources`), for admins: a card for each (ResourceCard: its
  // type, settings, secret alias, last check, use, holders, waiters and the pipelines that declare
  // it), a way to define one, to change its capacity and whether it is enabled (the dialog says what
  // that does), to delete one after a preview, and to make a holder let go after a confirmation;
  // below, the keystore (SecretsPanel). The page is read again every 3 s while the tab is shown, as
  // the runs are; a check is made only when the admin asks. What the Engine tells of its resource
  // types (`GET /api/v1/resource-types`, ADR-021), which the forms of `jdbc-pool` and
  // `openai-compatible` take their choices from, is read once, when the page is opened: it does not
  // change while the Engine runs. When it cannot be read the page says so, and those two types can
  // be neither defined nor changed; nothing else depends on it.
  interface Props {
    /** Where the time comes from; the tests give their own. */
    clock?: Clock;
    visibility?: Visibility;
  }
  let { clock = browserClock, visibility = pageVisibility }: Props = $props();

  const { i18n, api } = useApp();
  const EVERY_MS = 3000;

  // The clock and the visibility of a page are the same for as long as it lives.
  // svelte-ignore state_referenced_locally
  const list = createPolled({
    load: () => api.resources(),
    clock,
    visibility,
    intervalMs: EVERY_MS,
  });
  $effect(() => {
    void list.start();
    return () => list.dispose();
  });

  /** The catalog of resource types: undefined while it is read, then it or why it could not be. */
  let catalog = $state.raw<{ types: ResourceTypeCatalog } | { failure: ApiFailure } | undefined>(undefined);
  $effect(() => {
    let current = true;
    api.resourceTypes().then(
      (types) => current && (catalog = { types }),
      (error) => current && (catalog = { failure: asFailure(error) }),
    );
    return () => {
      current = false;
    };
  });
  const catalogTypes = $derived(catalog !== undefined && 'types' in catalog ? catalog.types : null);
  const catalogFailure = $derived(catalog !== undefined && 'failure' in catalog ? catalog.failure : null);

  const resources = $derived<Resource[]>([...(list.data ?? [])].sort((a, b) => a.name.localeCompare(b.name)));
  const updated = $derived(list.updatedAt === 0 ? '' : new Date(list.updatedAt).toLocaleTimeString(i18n.locale));

  // What is open: the form (to define, or to change one), and the question of making a holder let go.
  let form = $state<{ resource?: Resource } | null>(null);
  let deleting = $state<string | null>(null);
  let releasing = $state<{ resource: Resource; holder: Holder } | null>(null);
  let releaseBusy = $state(false);
  let releaseFailure = $state.raw<ApiFailure | null>(null);

  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

  function formDone(done: Resource | null) {
    form = null;
    if (done !== null) list.reload();
  }

  async function confirmRelease() {
    if (releasing === null || releaseBusy) return;
    releaseBusy = true;
    releaseFailure = null;
    try {
      await api.releaseHolder(releasing.resource.name, releasing.holder.runId);
      releasing = null;
      list.reload();
    } catch (error) {
      releaseFailure = asFailure(error);
    } finally {
      releaseBusy = false;
    }
  }
</script>

<div class="rl-page-head">
  <div>
    <h1>{i18n.t('page.resources.title')}</h1>
    <p class="sub">{i18n.t('page.resources.intro')}</p>
  </div>
  <div class="rl-actions">
    <button class="rl-btn primary" type="button" onclick={() => (form = {})}>{i18n.t('resources.define')}</button>
  </div>
</div>

{#if list.status === 'failed' && list.error}
  <div class="rl-stack">
    <ApiErrorNotice failure={list.error} />
    <div><button class="rl-btn retry" type="button" onclick={() => list.reload()}>{i18n.t('common.retry')}</button></div>
  </div>
{:else if list.status === 'loading' || catalog === undefined}
  <p class="muted" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
{:else}
  <div class="rl-toolbar">
    <div class="grow"></div>
    <label class="auto-label">
      <input type="checkbox" checked={list.auto} onchange={(event) => list.setAuto(event.currentTarget.checked)} />
      {i18n.t('resources.auto')}
    </label>
    <button class="rl-btn small refresh" type="button" onclick={() => list.reload()}>{i18n.t('resources.refresh')}</button>
    {#if updated}<span class="rl-help">{i18n.t('resources.updated', { time: updated })}</span>{/if}
  </div>

  {#if list.error}
    <div class="problem"><ApiErrorNotice failure={list.error} /></div>
  {/if}
  {#if catalogFailure}
    <div class="problem catalog-failed">
      <p class="rl-notice warning">{i18n.t('resources.catalog.failed')}</p>
      <ApiErrorNotice failure={catalogFailure} />
    </div>
  {/if}

  {#if resources.length === 0}
    <div class="rl-empty">
      <strong>{i18n.t('resources.empty.title')}</strong>
      <p>{i18n.t('resources.empty.body')}</p>
      <button class="rl-btn primary" type="button" onclick={() => (form = {})}>{i18n.t('resources.define')}</button>
    </div>
  {:else}
    <div class="grid">
      {#each resources as resource (resource.name)}
        <ResourceCard
          {resource}
          onchange={() => (form = { resource })}
          ondelete={() => (deleting = resource.name)}
          onrelease={(holder) => {
            releasing = { resource, holder };
            releaseFailure = null;
          }}
          onchecked={() => list.reload()}
        />
      {/each}
    </div>
  {/if}
{/if}

<SecretsPanel {clock} {visibility} />

{#if form}
  <ResourceFormDialog resource={form.resource} catalog={catalogTypes} onfinished={formDone} />
{/if}

{#if deleting}
  <ResourceDeleteDialog
    name={deleting}
    onfinished={(deleted) => {
      deleting = null;
      if (deleted) list.reload();
    }}
  />
{/if}

{#if releasing}
  <ConfirmDialog
    title={i18n.t('resources.release.title', { resource: releasing.resource.name })}
    confirmLabel={i18n.t('resources.release.confirm')}
    danger
    busy={releaseBusy}
    failure={releaseFailure}
    onconfirm={confirmRelease}
    oncancel={() => (releasing = null)}
  >
    <p>
      {i18n.t('resources.release.body', {
        run: releasing.holder.runId.slice(0, 8),
        pipeline: releasing.holder.pipeline,
        resource: releasing.resource.name,
      })}
    </p>
  </ConfirmDialog>
{/if}

<style>
  .muted {
    color: var(--text-secondary);
  }
  .problem {
    margin-bottom: var(--space-4);
  }
  .auto-label {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(22rem, 1fr));
    gap: var(--space-5);
  }
  p {
    margin: 0;
  }
</style>
