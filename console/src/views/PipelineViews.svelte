<script lang="ts">
  import { untrack } from 'svelte';
  import { ApiFailure } from '../api/failure';
  import type { Artifact, Pipeline } from '../api/model';
  import type { ViewDeclaration, ViewList, ViewPage } from '../api/views-model';
  import { useApp } from '../app/context';
  import { pipelineHref } from '../app/links';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Dialog from '../ui/Dialog.svelte';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import ViewFailure from './ViewFailure.svelte';
  import ViewParamsForm from './ViewParamsForm.svelte';
  import ViewTable from './ViewTable.svelte';
  import { invalidParameters, readViewState, viewHref, type ViewState } from './view-state';

  // The data of a pipeline (ADR-033, WI-1302): the views its version declares, and, for the one
  // chosen, its inputs, a table, paging and the drill into another view. The page keeps the list;
  // the chosen view is in a large popup (#29) that is open exactly when the address names a view:
  // nothing else says whether it is. The view is read-only and
  // the Engine runs it; this only asks and shows. Which view, the values and the page are in the
  // address, so that a reload, a link and the back button of the browser find the same table, and
  // coming back from a drill finds the view as it was left. The title, the labels and the cells are
  // text of the pipeline and its database and are shown as text only (ADR-017). A pipeline that
  // declares no view gets no section. The version is the page's: the uploader is the one the page
  // is of (an admin who can see several was asked which before getting here).
  interface Props {
    artifact: Artifact;
    pipeline: Pipeline;
  }
  let { artifact, pipeline }: Props = $props();

  const { i18n, api, router } = useApp();
  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

  const hrefOf = (state: ViewState) => viewHref(artifact.contentHash, pipeline.name, artifact.uploader, state);
  const roleHref = (role: string) => pipelineHref(artifact.contentHash, pipeline.name, artifact.uploader, role);

  // ---- the views the version declares -------------------------------------------------------

  let list = $state.raw<ViewList | null>(null);
  let listFailure = $state.raw<ApiFailure | null>(null);
  let listTicket = 0;
  async function loadList() {
    const ticket = ++listTicket;
    listFailure = null;
    try {
      const read = await api.pipelineViews(artifact.contentHash, pipeline.name, artifact.uploader);
      if (ticket === listTicket) list = read;
    } catch (error) {
      if (ticket === listTicket) listFailure = asFailure(error);
    }
  }
  const whose = $derived(JSON.stringify([artifact.contentHash, artifact.uploader, pipeline.name]));
  $effect(() => {
    void whose;
    list = null;
    void loadList();
    return () => {
      listTicket += 1;
    };
  });

  // ---- what the address says ----------------------------------------------------------------

  const shown = $derived(readViewState(router.search));
  const current = $derived<ViewDeclaration | null>(list?.views.find((v) => v.id === shown.view) ?? null);
  const undeclared = $derived(list !== null && shown.view !== null && current === null);
  const titles = $derived(Object.fromEntries((list?.views ?? []).map((v) => [v.id, v.title])));
  // The values of the parameters the view declares: whatever else the address carries is not asked.
  const applied = $derived(
    current === null ? {} : Object.fromEntries(current.parameters.map((p) => [p.name, shown.parameters[p.name] ?? ''])),
  );

  // ---- the page on show ---------------------------------------------------------------------

  let page = $state.raw<ViewPage | null>(null);
  let pageFailure = $state.raw<ApiFailure | null>(null);
  let reading = $state(false);
  let again = $state(0);
  let readTicket = 0;
  $effect(() => {
    void again;
    const view = current;
    const wanted = { values: applied, offset: shown.offset };
    if (view === null || invalidParameters(view, wanted.values).length > 0) {
      page = null;
      pageFailure = null;
      reading = false;
      return;
    }
    const ticket = ++readTicket;
    // What is on show stays while the next page is read, unless it is another view's.
    if (untrack(() => page?.view) !== view.id) page = null;
    pageFailure = null;
    reading = true;
    api
      .readView(artifact.contentHash, pipeline.name, view.id, {
        uploader: artifact.uploader,
        offset: wanted.offset > 0 ? wanted.offset : undefined,
        parameters: wanted.values,
      })
      .then((read) => {
        if (ticket === readTicket) page = read;
      })
      .catch((error: unknown) => {
        if (ticket !== readTicket) return;
        page = null;
        pageFailure = asFailure(error);
      })
      .finally(() => {
        if (ticket === readTicket) reading = false;
      });
    return () => {
      readTicket += 1;
    };
  });

  // ---- moving about -------------------------------------------------------------------------

  // The addresses of the views a drill left, the last one first to come back to. Only a drill
  // fills it; the back button of the browser empties it as it goes.
  let trail = $state.raw<string[]>([]);
  const here = $derived(hrefOf(shown));
  $effect(() => {
    if (trail.length > 0 && trail[trail.length - 1] === here) trail = trail.slice(0, -1);
  });
  const cameFrom = $derived(trail.length > 0 ? trail[trail.length - 1] : null);
  const cameFromTitle = $derived.by(() => {
    if (cameFrom === null) return '';
    const id = readViewState(new URL(cameFrom, 'http://x').search).view;
    return (id !== null ? titles[id] : undefined) ?? id ?? '';
  });

  function open(id: string) {
    trail = [];
    router.navigate(hrefOf({ view: id, parameters: {}, offset: 0 }));
  }
  function close() {
    trail = [];
    router.navigate(hrefOf({ view: null, parameters: {}, offset: 0 }));
  }
  function apply(values: Record<string, string>) {
    router.navigate(hrefOf({ view: shown.view, parameters: values, offset: 0 }));
    again += 1;
  }
  function turn(offset: number) {
    router.replace(hrefOf({ view: shown.view, parameters: shown.parameters, offset }));
  }
  function drill(target: ViewState) {
    trail = [...trail, here];
    router.navigate(hrefOf(target));
  }
  function back() {
    if (cameFrom === null) return;
    const to = cameFrom;
    trail = trail.slice(0, -1);
    router.navigate(to);
  }

  const statusLabel = (status: string) =>
    i18n.translate.has(`views.status.${status}`) ? i18n.translate(`views.status.${status}`) : status;
  const problemText = (view: ViewDeclaration) =>
    i18n.translate.has(`views.problem.${view.status}`)
      ? i18n.translate(`views.problem.${view.status}`, { role: view.resource })
      : i18n.t('views.problem.other', { role: view.resource });
</script>

{#if listFailure}
  <section class="rl-card views" aria-labelledby="views-title">
    <h2 id="views-title">{i18n.t('views.title')}</h2>
    <div class="rl-stack">
      <ApiErrorNotice failure={listFailure} />
      <div><button class="rl-btn retry" type="button" onclick={() => void loadList()}>{i18n.t('common.retry')}</button></div>
    </div>
  </section>
{:else if list !== null && list.views.length > 0}
  <section class="rl-card views" aria-labelledby="views-title">
    <h2 id="views-title">{i18n.t('views.title')}</h2>

    <p class="rl-help">{i18n.t('views.intro')}</p>
      <ul class="view-list">
        {#each list.views as view (view.id)}
          <li class="view" data-view={view.id}>
            <div class="head">
              <h3 class="view-title"><PlainText value={view.title} /></h3>
              <span class="view-status" class:ok={view.status === 'ready'}>{statusLabel(view.status)}</span>
            </div>
            {#if view.description !== ''}
              <p class="view-description"><PlainText value={view.description} multiline /></p>
            {/if}
            {#if view.status !== 'ready'}
              <p class="view-problem">
                {problemText(view)}
                <Link href={roleHref(view.resource)}>{i18n.t('views.problem.fix', { role: view.resource })}</Link>
              </p>
            {/if}
            <div>
              <button class="rl-btn small open" type="button" disabled={view.status !== 'ready'} onclick={() => open(view.id)}>
                {i18n.t('views.open')}
              </button>
            </div>
          </li>
        {/each}
      </ul>
    {#if shown.view !== null}
     <Dialog size="wide" title={current?.title ?? i18n.t('views.title')} onclose={close}>
      {#if cameFrom !== null}
        <div class="nav">
          <button class="rl-btn small back" type="button" onclick={back}>
            {i18n.t('views.back', { title: cameFromTitle })}
          </button>
        </div>
      {/if}

      {#if undeclared}
        <ViewFailure failure={new ApiFailure(404, { error: 'view_not_found' })} {roleHref} />
      {:else if current !== null}
        <h3 class="view-heading"><PlainText value={current.title} /></h3>
        {#if current.description !== ''}
          <p class="view-description"><PlainText value={current.description} multiline /></p>
        {/if}

        {#if current.parameters.length > 0}
          <ViewParamsForm view={current} {applied} onapply={apply} />
        {/if}

        {#if pageFailure}
          <ViewFailure failure={pageFailure} onretry={() => (again += 1)} {roleHref} />
        {:else if page !== null}
          <div class="results" aria-busy={reading}>
            {#if page.rows.length === 0}
              <p class="view-empty">{i18n.t('views.empty')}</p>
            {:else}
              <ViewTable {page} {titles} ondrill={drill} />
            {/if}
            <nav class="pager" aria-label={i18n.t('views.pager')}>
              <button class="rl-btn small prev" type="button" disabled={page.offset === 0 || reading} onclick={() => turn(Math.max(0, page!.offset - page!.limit))}>
                {i18n.t('common.prev')}
              </button>
              {#if page.rows.length > 0}
                <span class="view-range">
                  {i18n.t('views.range', { from: page.offset + 1, to: page.offset + page.rows.length })}
                </span>
              {/if}
              <button class="rl-btn small next" type="button" disabled={!page.hasMore || reading} onclick={() => turn(page!.offset + page!.limit)}>
                {i18n.t('common.next')}
              </button>
            </nav>
          </div>
        {:else if reading}
          <p class="muted" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
        {/if}
      {/if}
     </Dialog>
    {/if}
  </section>
{/if}

<style>
  .muted {
    color: var(--text-secondary);
  }
  p {
    margin: 0;
  }
  .view-list {
    display: grid;
    gap: var(--space-3);
    margin: var(--space-3) 0 0;
    padding: 0;
    list-style: none;
  }
  .view {
    display: grid;
    gap: var(--space-2);
    padding: var(--space-3) var(--space-4);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    background: var(--surface-subtle);
  }
  .head {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: var(--space-2) var(--space-3);
  }
  h3 {
    margin: 0;
    font-size: var(--text-md);
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  .view-heading {
    margin: var(--space-3) 0 var(--space-1);
  }
  .view-status {
    margin-left: auto;
    color: var(--warning-text);
    font-size: var(--text-sm);
    font-weight: 600;
  }
  .view-status.ok {
    color: var(--success-text);
  }
  .view-problem {
    color: var(--warning-text);
    font-weight: 600;
  }
  .nav {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
    margin-bottom: var(--space-2);
  }
  .results {
    display: grid;
    gap: var(--space-3);
  }
  .results[aria-busy='true'] {
    opacity: 0.6;
  }
  .pager {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-3);
  }
  .view-range {
    color: var(--text-secondary);
    font-size: var(--text-sm);
  }
</style>
