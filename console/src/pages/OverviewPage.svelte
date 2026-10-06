<script lang="ts">
  import { createPolled } from '../api/polled.svelte';
  import type { Run } from '../api/model';
  import { useApp } from '../app/context';
  import { runHref } from '../app/links';
  import { isTerminal, browserClock, pageVisibility, type Clock, type Visibility } from '../runs/run-watch.svelte';
  import { formatDuration, formatNumber } from '../i18n/format';
  import { runDurationMs } from '../runs/duration';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Badge from '../ui/Badge.svelte';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // What is going on, for the caller: the newest runs the Engine gives (`GET /api/v1/runs?limit=200`)
  // and the pipelines it gives (`GET /api/v1/definitions`), counted here. What the caller may see is
  // the Engine's decision, so these are the caller's numbers. Kept up to date while the tab is shown.
  interface Props {
    /** Where the time comes from; the tests give their own. */
    clock?: Clock;
    visibility?: Visibility;
  }
  let { clock = browserClock, visibility = pageVisibility }: Props = $props();

  const { i18n, api } = useApp();
  const RECENT = 8;
  const READ = 200;

  // The clock and the visibility of a page are the same for as long as it lives.
  // svelte-ignore state_referenced_locally
  const data = createPolled({
    load: async () => {
      const [runs, definitions] = await Promise.all([api.runs({ limit: READ }), api.definitions()]);
      return { runs, definitions: definitions.definitions };
    },
    clock,
    visibility,
    intervalMs: 3000,
  });
  $effect(() => {
    void data.start();
    return () => data.dispose();
  });

  const runs = $derived<Run[]>(data.data?.runs ?? []);
  const definitions = $derived(data.data?.definitions ?? []);
  const now = $derived(data.updatedAt || Date.now());
  const startOfToday = $derived.by(() => {
    const day = new Date(now);
    day.setHours(0, 0, 0, 0);
    return day.getTime();
  });
  const today = $derived(runs.filter((run) => Date.parse(run.createdAt) >= startOfToday));
  const numbers = $derived([
    { id: 'runsToday', value: today.length },
    { id: 'active', value: runs.filter((run) => !isTerminal(run.state)).length },
    {
      id: 'failedToday',
      value: today.filter((run) => run.state === 'FAILED' || run.state === 'TIMED_OUT').length,
    },
    { id: 'unsafe', value: definitions.filter((d) => d.verdict === 'UNSAFE').length },
    { id: 'pipelines', value: definitions.length },
  ] as const);
  const durationOf = (run: Run) => {
    const ms = runDurationMs(run, now);
    return ms === null ? '–' : formatDuration(ms, i18n.locale);
  };
</script>

<div class="rl-page-head">
  <div>
    <h1>{i18n.t('page.overview.title')}</h1>
    <p class="sub">{i18n.t('page.overview.intro')}</p>
  </div>
  <div class="rl-actions">
    <Link href="/upload" class="rl-btn">{i18n.t('overview.upload')}</Link>
    <Link href="/runs/new" class="rl-btn primary">{i18n.t('runs.createRun')}</Link>
  </div>
</div>

{#if data.status === 'failed' && data.error}
  <div class="rl-stack">
    <ApiErrorNotice failure={data.error} />
    <div><button class="rl-btn retry" type="button" onclick={() => data.reload()}>{i18n.t('common.retry')}</button></div>
  </div>
{:else if data.status === 'loading'}
  <p class="muted" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
{:else if runs.length === 0 && definitions.length === 0}
  <div class="rl-empty">
    <strong>{i18n.t('overview.empty.title')}</strong>
    <p>{i18n.t('overview.empty.body')}</p>
    <Link href="/upload" class="rl-btn primary">{i18n.t('overview.upload')}</Link>
  </div>
{:else}
  {#if data.error}
    <ApiErrorNotice failure={data.error} />
  {/if}
  <div class="tiles">
    {#each numbers as tile (tile.id)}
      <div class="tile rl-card">
        <span class="label">{i18n.t(`overview.${tile.id}`)}</span>
        <span class="value">{formatNumber(tile.value, i18n.locale)}</span>
        {#if tile.id === 'unsafe'}
          <span class="help rl-help">{i18n.t('overview.unsafeHelp')}</span>
        {/if}
      </div>
    {/each}
  </div>
  <p class="scope rl-help">{i18n.t('overview.scope', { count: runs.length })}</p>

  <div class="rl-page-head recent-head">
    <h2>{i18n.t('overview.recent')}</h2>
    <Link href="/runs">{i18n.t('overview.viewAll')}</Link>
  </div>
  {#if runs.length === 0}
    <p class="rl-empty">{i18n.t('runs.empty.title')}</p>
  {:else}
    <div class="rl-table-wrap">
      <table class="rl-table">
        <thead>
          <tr>
            <th scope="col">{i18n.t('runs.col.run')}</th>
            <th scope="col">{i18n.t('runs.col.pipeline')}</th>
            <th scope="col">{i18n.t('runs.col.state')}</th>
            <th scope="col">{i18n.t('runs.col.duration')}</th>
            <th scope="col">{i18n.t('runs.col.created')}</th>
          </tr>
        </thead>
        <tbody>
          {#each runs.slice(0, RECENT) as run (run.runId)}
            <tr>
              <td><Link href={runHref(run.runId)} class="rl-mono">{run.runId.slice(0, 8)}</Link></td>
              <td class="pipeline"><PlainText value={run.pipeline} mono /></td>
              <td class="state"><Badge kind="runState" value={run.state} /></td>
              <td class="rl-mono rl-nowrap">{durationOf(run)}</td>
              <td class="rl-nowrap"><Timestamp iso={run.createdAt} relativeTo={new Date(now)} /></td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  {/if}
{/if}

<style>
  .muted {
    color: var(--text-secondary);
  }
  .tiles {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr));
    gap: var(--space-4);
  }
  .tile {
    display: grid;
    gap: var(--space-1);
    padding: var(--space-4) var(--space-5);
  }
  .label {
    color: var(--text-muted);
    font-size: var(--text-2xs);
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  .value {
    font-family: var(--font-mono);
    font-size: 1.75rem;
    font-weight: 600;
  }
  .scope {
    margin: var(--space-3) 0 var(--space-5);
  }
  .recent-head {
    align-items: baseline;
    margin-bottom: var(--space-3);
  }
  .recent-head h2 {
    font-size: var(--text-md);
    font-weight: 600;
  }
</style>
