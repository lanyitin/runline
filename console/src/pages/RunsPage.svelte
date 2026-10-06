<script lang="ts">
  import { createPolled } from '../api/polled.svelte';
  import type { Run } from '../api/model';
  import { useApp } from '../app/context';
  import { runHref } from '../app/links';
  import { shortHash } from '../engine/info';
  import { formatDuration } from '../i18n/format';
  import { enumLabel } from '../i18n/enums';
  import { runDurationMs } from '../runs/duration';
  import { browserClock, pageVisibility, type Clock, type Visibility } from '../runs/run-watch.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Badge from '../ui/Badge.svelte';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // The runs of the caller (`GET /api/v1/runs`), the newest first, kept up to date while the tab is
  // shown. The pipeline and the number to read are asked of the Engine; the state is a filter of what
  // was read. The filters are in the address, so that a link keeps them.
  interface Props {
    /** Where the time comes from; the tests give their own. */
    clock?: Clock;
    visibility?: Visibility;
  }
  let { clock = browserClock, visibility = pageVisibility }: Props = $props();

  const { i18n, api, router } = useApp();
  const STATES = [
    'QUEUED',
    'WAITING_FOR_RESOURCES',
    'INITIALIZING',
    'RUNNING',
    'TIMED_OUT_UNFINISHED',
    'SUCCEEDED',
    'FAILED',
    'CANCELLED',
    'INTERRUPTED',
    'TIMED_OUT',
  ];
  const LIMITS = [50, 100, 200];
  const DEFAULT_LIMIT = 50;
  const INTERVAL_MS = 3000;

  const start = new URLSearchParams(router.search);
  let pipeline = $state(start.get('pipeline') ?? '');
  let stateFilter = $state(start.get('state') ?? '');
  const startLimit = Number(start.get('limit'));
  let limit = $state(LIMITS.includes(startLimit) ? startLimit : DEFAULT_LIMIT);

  // The pipelines to choose from are those seen in what was read: a filter shows only one, and the
  // others must stay in the list to choose from.
  let seen = $state<string[]>(start.get('pipeline') ? [start.get('pipeline')!] : []);

  // The clock and the visibility of a page are the same for as long as it lives.
  // svelte-ignore state_referenced_locally
  const list = createPolled({
    load: async () => {
      const read = await api.runs({
        pipeline: pipeline === '' ? undefined : pipeline,
        limit: limit === DEFAULT_LIMIT ? undefined : limit,
      });
      const fresh = [...new Set(read.map((run) => run.pipeline))].filter((name) => !seen.includes(name));
      if (fresh.length > 0) seen = [...seen, ...fresh].sort();
      return read;
    },
    clock,
    visibility,
    intervalMs: INTERVAL_MS,
  });
  $effect(() => {
    void list.start();
    return () => list.dispose();
  });

  function filtered() {
    const query = new URLSearchParams();
    if (pipeline !== '') query.set('pipeline', pipeline);
    if (stateFilter !== '') query.set('state', stateFilter);
    if (limit !== DEFAULT_LIMIT) query.set('limit', String(limit));
    const text = query.toString();
    router.replace(text ? `/runs?${text}` : '/runs');
  }

  const runs = $derived<Run[]>(list.data ?? []);
  const shown = $derived(stateFilter === '' ? runs : runs.filter((run) => run.state === stateFilter));
  const now = $derived(list.updatedAt || Date.now());
  const updated = $derived(
    list.updatedAt === 0 ? '' : new Date(list.updatedAt).toLocaleTimeString(i18n.locale),
  );
  const durationOf = (run: Run) => {
    const ms = runDurationMs(run, now);
    return ms === null ? '–' : formatDuration(ms, i18n.locale);
  };
</script>

<div class="rl-page-head">
  <div>
    <h1>{i18n.t('page.runs.title')}</h1>
    <p class="sub">{i18n.t('page.runs.intro')}</p>
  </div>
  <div class="rl-actions">
    <Link href="/runs/new" class="rl-btn primary">{i18n.t('runs.createRun')}</Link>
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
    <div class="rl-field">
      <label for="runs-pipeline">{i18n.t('runs.filter.pipeline')}</label>
      <select
        id="runs-pipeline"
        class="rl-input"
        value={pipeline}
        onchange={(event) => {
          pipeline = event.currentTarget.value;
          filtered();
          list.reload();
        }}
      >
        <option value="">{i18n.t('runs.filter.allPipelines')}</option>
        {#each seen as name (name)}
          <option value={name}>{name}</option>
        {/each}
      </select>
    </div>
    <div class="rl-field">
      <label for="runs-state">{i18n.t('runs.filter.state')}</label>
      <select
        id="runs-state"
        class="rl-input"
        value={stateFilter}
        onchange={(event) => {
          stateFilter = event.currentTarget.value;
          filtered();
        }}
      >
        <option value="">{i18n.t('runs.filter.allStates')}</option>
        {#each STATES as name (name)}
          <option value={name}>{enumLabel(i18n.translate, 'runState', name)}</option>
        {/each}
      </select>
    </div>
    <div class="rl-field">
      <label for="runs-limit">{i18n.t('runs.filter.limit')}</label>
      <select
        id="runs-limit"
        class="rl-input"
        value={limit}
        onchange={(event) => {
          limit = Number(event.currentTarget.value);
          filtered();
          list.reload();
        }}
      >
        {#each LIMITS as count (count)}
          <option value={count}>{count}</option>
        {/each}
      </select>
    </div>
    <div class="grow"></div>
    <div class="live">
      <label class="auto-label">
        <input
          class="auto"
          type="checkbox"
          checked={list.auto}
          onchange={(event) => list.setAuto(event.currentTarget.checked)}
        />
        {i18n.t('runs.auto')}
      </label>
      <button class="rl-btn small refresh" type="button" onclick={() => list.reload()}>{i18n.t('runs.refresh')}</button>
      {#if updated}
        <span class="updated rl-help">{i18n.t('runs.updated', { time: updated })}</span>
      {/if}
    </div>
  </div>

  {#if list.error}
    <ApiErrorNotice failure={list.error} />
    <p class="rl-help">{i18n.t('runs.refreshFailed')}</p>
  {/if}

  {#if runs.length === 0 && pipeline === ''}
    <div class="rl-empty">
      <strong>{i18n.t('runs.empty.title')}</strong>
      <p>{i18n.t('runs.empty.body')}</p>
      <Link href="/runs/new" class="rl-btn primary">{i18n.t('runs.createRun')}</Link>
    </div>
  {:else if shown.length === 0}
    <p class="rl-empty">{runs.length === 0 ? i18n.t('runs.empty.title') : i18n.t('runs.noMatch')}</p>
  {:else}
    <div class="rl-table-wrap">
      <table class="rl-table">
        <thead>
          <tr>
            <th scope="col">{i18n.t('runs.col.run')}</th>
            <th scope="col">{i18n.t('runs.col.pipeline')}</th>
            <th scope="col">{i18n.t('runs.col.state')}</th>
            <th scope="col">{i18n.t('runs.col.source')}</th>
            <th scope="col">{i18n.t('runs.col.duration')}</th>
            <th scope="col">{i18n.t('runs.col.created')}</th>
          </tr>
        </thead>
        <tbody>
          {#each shown as run (run.runId)}
            <tr>
              <td>
                <Link href={runHref(run.runId)} class="run-id rl-mono" title={run.runId}>{run.runId.slice(0, 8)}</Link>
              </td>
              <td>
                <div class="pipeline"><PlainText value={run.pipeline} mono /></div>
                <div class="version"><span class="hash rl-mono" title={run.contentHash}>{shortHash(run.contentHash)}</span></div>
              </td>
              <td><Badge kind="runState" value={run.state} /></td>
              <td class="source">
                <span class="kind">{enumLabel(i18n.translate, 'runSource', run.source.kind)}</span>
                <PlainText value={run.source.name} />
              </td>
              <td class="duration rl-mono rl-nowrap">{durationOf(run)}</td>
              <td class="rl-nowrap"><Timestamp iso={run.createdAt} relativeTo={new Date(now)} /></td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
    <p class="count rl-help">{i18n.t('runs.count', { shown: shown.length, loaded: runs.length })}</p>
    {#if runs.length >= limit}
      <p class="rl-help">{i18n.t('runs.limitReached', { count: limit })}</p>
    {/if}
  {/if}
{/if}

<style>
  .muted {
    color: var(--text-secondary);
  }
  .live {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-3);
  }
  .auto-label {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
  }
  .pipeline {
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  .version {
    color: var(--text-muted);
    font-size: var(--text-xs);
  }
  .kind {
    margin-right: var(--space-1);
    color: var(--text-muted);
    font-size: var(--text-2xs);
    font-weight: 600;
  }
</style>
