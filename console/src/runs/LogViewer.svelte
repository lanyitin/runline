<script lang="ts">
  import { tick } from 'svelte';
  import type { LogEntry } from '../api/model';
  import type { WatchPhase } from './run-watch.svelte';
  import { useApp } from '../app/context';
  import { enumLabel } from '../i18n/enums';
  import { formatTimeOfDay } from '../i18n/format';
  import PlainText from '../ui/PlainText.svelte';

  // The log of a run as a terminal panel: the lines appended as they come, each with its sequence
  // number, its time and the stream it was written to (standard error is told from standard output
  // by its words as well as its colour), what the poll is doing in words, filters, the switch that
  // follows the end, and a download. A line is text, whatever it says (ADR-017).
  interface Props {
    entries: readonly LogEntry[];
    phase: WatchPhase;
    failures: number;
    /** The wait before the next read after a failure. */
    retryInMs: number | null;
    missing: number;
    /** Whether the run has ended: an empty log then means there is none, not that none came yet. */
    ended: boolean;
    runId: string;
  }
  let { entries, phase, failures, retryInMs, missing, ended, runId }: Props = $props();

  const { i18n } = useApp();
  /** The most lines drawn: a log of a hundred thousand lines would stop the page. The download has them all. */
  const DRAWN = 5000;

  let stream = $state('');
  let search = $state('');
  let follow = $state(true);
  let panel: HTMLElement | undefined;

  const matching = $derived.by(() => {
    const word = search.trim().toLowerCase();
    return entries.filter(
      (entry) =>
        (stream === '' || entry.stream === stream) &&
        (word === '' || entry.line.toLowerCase().includes(word)),
    );
  });
  const drawn = $derived(matching.length > DRAWN ? matching.slice(-DRAWN) : matching);

  // The end of the panel stays in view while the lines come, if the person asked for it.
  $effect(() => {
    void drawn.length;
    if (follow && panel) {
      const element = panel;
      void tick().then(() => (element.scrollTop = element.scrollHeight));
    }
  });

  const statusText = $derived.by(() => {
    switch (phase) {
      case 'loading':
        return i18n.t('log.status.loading');
      case 'live':
        return i18n.t('log.status.live');
      case 'paused':
        return i18n.t('log.status.paused');
      case 'retrying':
        return i18n.t('log.status.retrying', {
          failures,
          seconds: Math.round((retryInMs ?? 0) / 1000),
        });
      case 'done':
        return i18n.t('log.status.done');
      case 'gone':
        return i18n.t('log.status.gone');
      case 'signed-out':
        return i18n.t('log.status.signedOut');
    }
  });

  function download() {
    const text = entries.map((e) => `${e.at} ${e.stream} ${e.line}`).join('\n') + '\n';
    const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
    const link = document.createElement('a');
    link.href = url;
    link.download = `run-${runId.slice(0, 8)}.log`;
    link.click();
    URL.revokeObjectURL(url);
  }
</script>

<section class="rl-card log" aria-labelledby="log-title">
  <div class="bar">
    <h2 id="log-title">{i18n.t('log.title')}</h2>
    <span class="lines rl-help">{i18n.t('log.lines', { count: entries.length })}</span>
  </div>

  <div class="rl-toolbar">
    <div class="rl-field">
      <label for="log-stream">{i18n.t('log.filter.stream')}</label>
      <select
        id="log-stream"
        class="rl-input stream-filter"
        value={stream}
        onchange={(event) => (stream = event.currentTarget.value)}
      >
        <option value="">{i18n.t('log.filter.all')}</option>
        <option value="STDOUT">{enumLabel(i18n.translate, 'logStream', 'STDOUT')}</option>
        <option value="STDERR">{enumLabel(i18n.translate, 'logStream', 'STDERR')}</option>
      </select>
    </div>
    <div class="rl-field grow">
      <label for="log-search">{i18n.t('log.search')}</label>
      <input id="log-search" class="rl-input search" type="search" bind:value={search} />
    </div>
    <label class="follow-label">
      <input class="follow" type="checkbox" bind:checked={follow} />
      {i18n.t('log.follow')}
    </label>
    <button class="rl-btn small download" type="button" disabled={entries.length === 0} onclick={download}>
      {i18n.t('log.download')}
    </button>
  </div>

  <p class="log-status" role="status" class:problem={phase === 'retrying' || phase === 'gone'}>{statusText}</p>
  {#if missing > 0}
    <p class="missing rl-notice warning">{i18n.t('log.missing', { count: missing })}</p>
  {/if}
  {#if stream !== '' || search.trim() !== ''}
    <p class="matches rl-help">{i18n.t('log.matches', { shown: matching.length, total: entries.length })}</p>
  {/if}
  {#if matching.length > DRAWN}
    <p class="truncated rl-help">{i18n.t('log.truncated', { shown: DRAWN, total: matching.length })}</p>
  {/if}

  <!-- A panel that scrolls must be reached by the keyboard to be scrolled by it. -->
  <!-- svelte-ignore a11y_no_noninteractive_tabindex -->
  <div class="panel" bind:this={panel} tabindex="0" role="log" aria-label={i18n.t('log.title')}>
    {#if entries.length === 0}
      <p class="empty">
        {ended || phase === 'gone' ? i18n.t('log.empty.done') : i18n.t('log.empty.running')}
      </p>
    {/if}
    {#each drawn as entry (entry.seq)}
      <div class="line" class:stderr={entry.stream === 'STDERR'}>
        <span class="seq">{entry.seq}</span>
        <time datetime={entry.at} title={entry.at}>{formatTimeOfDay(entry.at, i18n.locale)}</time>
        <span class="stream">{entry.stream}</span>
        <span class="text"><PlainText value={entry.line} mono multiline /></span>
      </div>
    {/each}
  </div>
</section>

<style>
  .bar {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    gap: var(--space-3);
  }
  .follow-label {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding-bottom: var(--space-2);
  }
  .log-status {
    margin: 0 0 var(--space-3);
    color: var(--text-secondary);
  }
  .log-status.problem {
    color: var(--warning-text);
    font-weight: 600;
  }
  .missing {
    margin: 0 0 var(--space-3);
  }
  .matches,
  .truncated {
    margin: 0 0 var(--space-2);
  }
  /* A terminal panel, dark in the light theme as well (the design system). */
  .panel {
    max-height: 32rem;
    overflow: auto;
    padding: var(--space-3) 0;
    border-radius: var(--radius-md);
    background: #0b1220;
    color: #d6deeb;
    font-family: var(--font-mono);
    font-size: var(--text-xs);
    line-height: 1.6;
  }
  .empty {
    margin: 0;
    padding: var(--space-2) var(--space-4);
    color: #9aa7bd;
    font-family: var(--font-sans);
  }
  .line {
    display: grid;
    grid-template-columns: 4.5rem 6.5rem 3.75rem 1fr;
    gap: var(--space-3);
    padding: 0 var(--space-4);
    animation: appear 160ms ease-out;
  }
  .line:hover {
    background: rgba(148, 163, 184, 0.1);
  }
  .seq {
    color: #7b88a1;
    text-align: right;
    user-select: none;
  }
  time {
    color: #7b88a1;
  }
  .stream {
    color: #7b88a1;
    font-size: var(--text-2xs);
  }
  .line.stderr .stream,
  .line.stderr .text {
    color: #ff9b9b;
  }
  .line.stderr {
    background: rgba(239, 68, 68, 0.08);
  }
  @keyframes appear {
    from {
      opacity: 0;
    }
    to {
      opacity: 1;
    }
  }
</style>
