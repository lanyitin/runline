<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { Cancellation } from '../api/model';
  import { useApp } from '../app/context';
  import { newRunHref, pipelineHref } from '../app/links';
  import { shortHash } from '../engine/info';
  import { enumLabel } from '../i18n/enums';
  import { formatDuration } from '../i18n/format';
  import { runDurationMs } from '../runs/duration';
  import LogViewer from '../runs/LogViewer.svelte';
  import {
    browserClock,
    createRunWatch,
    isTerminal,
    pageVisibility,
    type Clock,
    type Visibility,
  } from '../runs/run-watch.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Badge from '../ui/Badge.svelte';
  import CopyButton from '../ui/CopyButton.svelte';
  import Link from '../ui/Link.svelte';
  import Notice from '../ui/Notice.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // One run: what it is, its state kept up to date, the cancel, what the pipeline reported if it
  // failed, and its log (ADR-017: read by polling, only while the run goes on and the tab is shown).
  interface Props {
    runId: string;
    /** Where the time comes from; the tests give their own. */
    clock?: Clock;
    visibility?: Visibility;
  }
  let { runId, clock = browserClock, visibility = pageVisibility }: Props = $props();

  const { i18n, api } = useApp();

  // The page of another run is another page (App.svelte keys it by the address), and the clock and
  // the visibility are the same for as long as it lives.
  // svelte-ignore state_referenced_locally
  const id = runId;
  // svelte-ignore state_referenced_locally
  const watch = createRunWatch({ runId: id, api, clock, visibility });
  $effect(() => {
    void watch.start();
    return () => watch.dispose();
  });

  const run = $derived(watch.run);
  const ended = $derived(run !== null && isTerminal(run.state));
  const now = $derived.by(() => {
    void watch.run;
    return Date.now();
  });
  const duration = $derived.by(() => {
    const ms = run ? runDurationMs(run, now) : null;
    return ms === null ? '–' : formatDuration(ms, i18n.locale);
  });

  let confirming = $state(false);
  let cancelling = $state(false);
  let outcome = $state.raw<Cancellation | ApiFailure | null>(null);

  async function cancel() {
    if (cancelling) return;
    cancelling = true;
    outcome = null;
    try {
      outcome = await api.cancelRun(id);
    } catch (error) {
      outcome = error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));
    } finally {
      cancelling = false;
      confirming = false;
      watch.refresh();
    }
  }
</script>

{#if run === null && watch.phase === 'gone'}
  <div class="rl-notice warning" role="status">
    <strong>{i18n.t('run.gone.title')}</strong>
    <p>{i18n.t('run.gone.body')}</p>
  </div>
  <p><Link href="/runs">{i18n.t('run.back')}</Link></p>
{:else if run === null}
  {#if watch.problem}
    <div class="rl-stack">
      <ApiErrorNotice failure={watch.problem} />
    </div>
  {:else}
    <p class="muted" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
  {/if}
{:else}
  <p class="back"><Link href="/runs">{i18n.t('run.back')}</Link></p>

  <div class="rl-page-head head">
    <div>
      <h1>{i18n.t('page.run.title')} <span class="rl-mono">{run.runId.slice(0, 8)}</span></h1>
      <p class="sub"><PlainText value={run.pipeline} mono /></p>
    </div>
    <div class="rl-actions">
      <Badge kind="runState" value={run.state} />
      {#if !ended}
        <button class="rl-btn danger cancel" type="button" onclick={() => (confirming = true)} disabled={confirming}>
          {i18n.t('run.cancel')}
        </button>
      {/if}
      <Link href={newRunHref(run.contentHash, run.pipeline, run.parameters, run.uploader)} class="rl-btn">
        {i18n.t('run.runAgain')}
      </Link>
    </div>
  </div>

  <div class="rl-stack">
    {#if confirming}
      <div class="rl-notice warning confirm" role="alertdialog" aria-label={i18n.t('run.cancel')}>
        <p>{i18n.t('run.cancel.ask')}</p>
        <div class="rl-actions">
          <button class="rl-btn danger solid confirm-cancel" type="button" disabled={cancelling} onclick={cancel}>
            {cancelling ? i18n.t('run.cancel.busy') : i18n.t('run.cancel.yes')}
          </button>
          <button class="rl-btn keep" type="button" disabled={cancelling} onclick={() => (confirming = false)}>
            {i18n.t('run.cancel.no')}
          </button>
        </div>
      </div>
    {/if}
    {#if outcome}
      <div class="cancel-result">
        {#if outcome instanceof ApiFailure}
          <ApiErrorNotice failure={outcome} />
        {:else}
          <Notice tone="info">
            {outcome.cancellation === 'cancelled' ? i18n.t('run.cancel.cancelled') : i18n.t('run.cancel.requested')}
          </Notice>
        {/if}
      </div>
    {/if}

    <section class="rl-card">
      <dl class="rl-dl facts">
        <dt>{i18n.t('run.id')}</dt>
        <dd class="id-line">
          <PlainText value={run.runId} mono />
          <CopyButton
            text={run.runId}
            label={i18n.t('common.copy')}
            copied={i18n.t('common.copied')}
            failed={i18n.t('common.copyFailed')}
          />
        </dd>
        <dt>{i18n.t('run.pipeline')}</dt>
        <dd>
          <Link href={pipelineHref(run.contentHash, run.pipeline, run.uploader)}><PlainText value={run.pipeline} mono /></Link>
        </dd>
        <dt>{i18n.t('run.version')}</dt>
        <dd>
          <span class="rl-mono" title={run.contentHash}>{shortHash(run.contentHash)}</span>
          <PlainText value={run.uploader} />
        </dd>
        <dt>{i18n.t('run.className')}</dt>
        <dd><PlainText value={run.className} mono /></dd>
        <dt>{i18n.t('run.source')}</dt>
        <dd>
          {enumLabel(i18n.translate, 'runSource', run.source.kind)}
          <PlainText value={run.source.name} />
        </dd>
        <dt>{i18n.t('run.created')}</dt>
        <dd><Timestamp iso={run.createdAt} /></dd>
        <dt>{i18n.t('run.started')}</dt>
        <dd>{#if run.startedAt}<Timestamp iso={run.startedAt} />{:else}{i18n.t('run.notStarted')}{/if}</dd>
        <dt>{i18n.t('run.finished')}</dt>
        <dd>{#if run.finishedAt}<Timestamp iso={run.finishedAt} />{:else}{i18n.t('run.notFinished')}{/if}</dd>
        <dt>{i18n.t('run.duration')}</dt>
        <dd class="rl-mono">{duration}</dd>
      </dl>
    </section>

    <section class="rl-card parameters">
      <h2>{i18n.t('run.parameters')}</h2>
      {#if Object.keys(run.parameters).length === 0}
        <p>{i18n.t('run.parameters.none')}</p>
      {:else}
        <table class="rl-table">
          <tbody>
            {#each Object.entries(run.parameters) as [name, value] (name)}
              <tr>
                <th scope="row"><PlainText value={name} mono /></th>
                <td><PlainText value={value} mono multiline /></td>
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    </section>

    {#if run.unsafeExecution}
      <section class="rl-card unsafe">
        <h2>{i18n.t('run.unsafe.title')}</h2>
        <dl class="rl-dl">
          <dt>{i18n.t('run.unsafe.setBy')}</dt>
          <dd><PlainText value={run.unsafeExecution.setBy} /></dd>
          <dt>{i18n.t('run.unsafe.setAt')}</dt>
          <dd><Timestamp iso={run.unsafeExecution.setAt} /></dd>
        </dl>
      </section>
    {/if}

    {#if run.failure}
      <section class="rl-card failure">
        <h2>{i18n.t('run.failure.title')}</h2>
        <dl class="rl-dl">
          <dt>{i18n.t('run.failure.type')}</dt>
          <dd><PlainText value={run.failure.type} mono /></dd>
          <dt>{i18n.t('run.failure.message')}</dt>
          <dd>
            {#if run.failure.message}
              <PlainText value={run.failure.message} multiline />
            {:else}
              <span class="rl-muted">{i18n.t('run.failure.noMessage')}</span>
            {/if}
          </dd>
        </dl>
        {#if run.failure.trace}
          <details>
            <summary>{i18n.t('run.failure.trace')}</summary>
            <pre><PlainText value={run.failure.trace} mono multiline /></pre>
          </details>
        {/if}
      </section>
    {/if}

    <LogViewer
      entries={watch.entries}
      phase={watch.phase}
      failures={watch.failures}
      retryInMs={watch.retryInMs}
      missing={watch.missing}
      {ended}
      runId={run.runId}
    />
  </div>
{/if}

<style>
  .muted {
    color: var(--text-secondary);
  }
  .back {
    margin: 0 0 var(--space-3);
  }
  .id-line {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }
  .parameters th {
    position: static;
    width: 14rem;
    text-transform: none;
    letter-spacing: 0;
    font-size: var(--text-sm);
    background: transparent;
    color: var(--text);
  }
  .failure {
    border-left: 3px solid var(--danger);
  }
  details {
    margin-top: var(--space-3);
  }
  summary {
    cursor: pointer;
    font-weight: 600;
  }
  pre {
    margin: var(--space-2) 0 0;
    padding: var(--space-3);
    overflow: auto;
    border-radius: var(--radius-md);
    background: var(--surface-subtle);
    font-size: var(--text-xs);
  }
</style>
