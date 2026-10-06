<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { Holder, Resource } from '../api/admin-model';
  import { createPolled } from '../api/polled.svelte';
  import ResourceFormDialog from '../admin/ResourceFormDialog.svelte';
  import { useApp } from '../app/context';
  import { runHref } from '../app/links';
  import { formatDuration } from '../i18n/format';
  import { browserClock, pageVisibility, type Clock, type Visibility } from '../runs/run-watch.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import ConfirmDialog from '../ui/ConfirmDialog.svelte';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // The shared resources (`GET /api/v1/resources`), for admins: for each its capacity and how much
  // of it is held, who holds it and who waits for it with the times, a way to define one, to change
  // its capacity and whether it is enabled (the dialog says what that does), and to make a holder let
  // go after a confirmation. The page is read again every 3 s while the tab is shown, as the runs are.
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

  const resources = $derived<Resource[]>([...(list.data ?? [])].sort((a, b) => a.name.localeCompare(b.name)));
  const updated = $derived(list.updatedAt === 0 ? '' : new Date(list.updatedAt).toLocaleTimeString(i18n.locale));
  const duration = (seconds: number) => formatDuration(Math.round(seconds) * 1000, i18n.locale);

  // What is open: the form (to define, or to change one), and the question of making a holder let go.
  let form = $state<{ resource?: Resource } | null>(null);
  let releasing = $state<{ resource: Resource; holder: Holder } | null>(null);
  let releaseBusy = $state(false);
  let releaseFailure = $state.raw<ApiFailure | null>(null);

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
      releaseFailure = error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));
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
{:else if list.status === 'loading'}
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

  {#if resources.length === 0}
    <div class="rl-empty">
      <strong>{i18n.t('resources.empty.title')}</strong>
      <p>{i18n.t('resources.empty.body')}</p>
      <button class="rl-btn primary" type="button" onclick={() => (form = {})}>{i18n.t('resources.define')}</button>
    </div>
  {:else}
    <div class="grid">
      {#each resources as resource (resource.name)}
        <article class="rl-card resource">
          <header>
            <h2 class="name"><PlainText value={resource.name} mono /></h2>
            <span class="state tag {resource.enabled ? 'on' : 'off'}">
              {resource.enabled ? i18n.t('resources.state.enabled') : i18n.t('resources.state.disabled')}
            </span>
          </header>

          <div class="meter">
            <progress value={resource.holders.length} max={resource.capacity}></progress>
            <span class="usage rl-mono">{i18n.t('resources.usage', { held: resource.holders.length, capacity: resource.capacity })}</span>
          </div>
          {#if resource.holders.length > resource.capacity}
            <p class="rl-help over">{i18n.t('resources.overCapacity')}</p>
          {/if}

          <section class="holders">
            <h3>{i18n.t('resources.holders')}</h3>
            {#if resource.holders.length === 0}
              <p class="rl-help">{i18n.t('resources.nobodyHolds')}</p>
            {:else}
              <ul>
                {#each resource.holders as holder (holder.runId)}
                  <li>
                    <Link href={runHref(holder.runId)} class="rl-mono">{holder.runId.slice(0, 8)}</Link>
                    <span class="pipeline"><PlainText value={holder.pipeline} mono /></span>
                    <span class="rl-help">
                      <Timestamp iso={holder.heldSince} />,
                      {i18n.t('resources.heldFor', { duration: duration(holder.heldSeconds) })}
                    </span>
                    <button class="rl-btn small danger" type="button" onclick={() => { releasing = { resource, holder }; releaseFailure = null; }}>
                      {i18n.t('resources.release')}
                    </button>
                  </li>
                {/each}
              </ul>
            {/if}
          </section>

          <section class="waiters">
            <h3>{i18n.t('resources.waiters')}</h3>
            {#if resource.waiters.length === 0}
              <p class="rl-help">{i18n.t('resources.nobodyWaits')}</p>
            {:else}
              <ol>
                {#each resource.waiters as waiter, index (waiter.runId)}
                  <li>
                    <span class="position rl-mono">{index + 1}</span>
                    <Link href={runHref(waiter.runId)} class="rl-mono">{waiter.runId.slice(0, 8)}</Link>
                    <span class="pipeline"><PlainText value={waiter.pipeline} mono /></span>
                    <span class="rl-help">
                      {i18n.t('resources.waitedFor', {
                        duration: duration(waiter.waitedSeconds),
                        names: waiter.waitingFor.join(', '),
                      })}
                    </span>
                  </li>
                {/each}
              </ol>
            {/if}
          </section>

          <footer>
            <span class="rl-help">
              {i18n.t('resources.madeBy', { by: resource.createdBy, updatedBy: resource.updatedBy })}
            </span>
            <button class="rl-btn small" type="button" onclick={() => (form = { resource })}>{i18n.t('resources.change')}</button>
          </footer>
        </article>
      {/each}
    </div>
  {/if}
{/if}

{#if form}
  <ResourceFormDialog resource={form.resource} onfinished={formDone} />
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
  .resource {
    display: grid;
    gap: var(--space-4);
    align-content: start;
  }
  header,
  footer {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-2);
  }
  h2.name {
    font-size: var(--text-md);
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  h3 {
    margin: 0 0 var(--space-2);
    color: var(--text-muted);
    font-size: var(--text-2xs);
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  .tag {
    padding: 2px var(--space-2);
    border-radius: var(--radius-pill);
    font-size: var(--text-2xs);
    font-weight: 700;
  }
  .tag.on {
    background: var(--success-tint);
    color: var(--success-text);
  }
  .tag.off {
    background: var(--neutral-tint);
    color: var(--neutral-text);
  }
  .meter {
    display: grid;
    gap: var(--space-1);
  }
  progress {
    width: 100%;
    height: 8px;
    accent-color: var(--accent);
  }
  .over {
    margin: 0;
    color: var(--warning-text);
  }
  ul,
  ol {
    display: grid;
    gap: var(--space-2);
    margin: 0;
    padding: 0;
    list-style: none;
  }
  li {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }
  .position {
    min-width: 1.5em;
    color: var(--text-muted);
  }
  .pipeline {
    font-weight: 600;
  }
  p {
    margin: 0;
  }
</style>
