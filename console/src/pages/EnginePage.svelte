<script lang="ts">
  import { useApp } from '../app/context';
  import { shortHash } from '../engine/info';
  import { formatDuration } from '../i18n/format';
  import CopyButton from '../ui/CopyButton.svelte';
  import StatusDot from '../ui/StatusDot.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // The Engine, as `GET /api/v1/system` says it (ADR-016): for developers and admins alike. What the
  // Engine chip shows in a popover, here as a page: the version, the full hash, the build time (the
  // time of the commit, and the page says so), the JDK, how long it has been up, the allow-list
  // version and where the API is.
  const { i18n, engineInfo, connection } = useApp();

  const current = $derived(engineInfo.state);
  const down = $derived(connection.state === 'down');
  const uptime = (system: { uptimeSeconds: number; receivedAt: number }) =>
    formatDuration(system.uptimeSeconds * 1000 + Math.max(0, Date.now() - system.receivedAt), i18n.locale);
</script>

<h1>{i18n.t('page.engine.title')}</h1>
<p class="intro">{i18n.t('page.engine.intro')}</p>

{#if current.status === 'failed'}
  <p class="state failed">
    <StatusDot tone="danger" />
    {i18n.t('engine.unavailable')}
    <button class="retry" type="button" onclick={() => engineInfo.reload()}>
      {i18n.t('engine.retry')}
    </button>
  </p>
{:else if current.status === 'ready' && current.info.system}
  {@const info = current.info}
  {@const system = current.info.system}
  <section class="card" aria-label={i18n.t('engine.details')}>
    <p class="live">
      <StatusDot tone={down ? 'danger' : 'success'} glow={!down} />
      {down ? i18n.t('engine.disconnected') : `v${info.version}`}
    </p>
    <dl>
      <dt>{i18n.t('engine.version')}</dt>
      <dd class="mono">{info.version}</dd>
      <dt>{i18n.t('engine.commitFull')}</dt>
      <dd class="full-hash">
        <span class="mono">
          {info.commitHash === 'unknown' ? i18n.t('engine.unknown') : info.commitHash}
        </span>
        {#if info.commitHash !== 'unknown'}
          <CopyButton
            text={info.commitHash}
            label={i18n.t('engine.copy')}
            copied={i18n.t('engine.copied')}
            failed={i18n.t('engine.copyFailed')}
          />
        {/if}
      </dd>
      {#if info.dirty}
        <dt>{i18n.t('engine.commit')}</dt>
        <dd>{i18n.t('engine.dirty')}</dd>
      {/if}
      <dt>{i18n.t('engine.buildTime')}</dt>
      <dd>
        <Timestamp iso={system.buildTime} />
        <span class="help">{i18n.t('engine.buildTimeHelp')}</span>
      </dd>
      <dt>{i18n.t('engine.jdk')}</dt>
      <dd class="mono">{system.jdk}</dd>
      <dt>{i18n.t('engine.uptime')}</dt>
      <dd class="mono">{uptime(system)}</dd>
      <dt>{i18n.t('engine.startedAt')}</dt>
      <dd><Timestamp iso={system.startedAt} /></dd>
      <dt>{i18n.t('engine.allowList')}</dt>
      <dd class="mono">{system.allowListVersion}</dd>
      <dt>{i18n.t('engine.apiBase')}</dt>
      <dd class="mono">{location.origin}</dd>
    </dl>
  </section>
{:else}
  <p class="state" role="status" aria-busy="true">
    <StatusDot tone="neutral" />
    {i18n.t('engine.loading')}
  </p>
{/if}

<style>
  h1 {
    font-size: var(--text-xl);
    font-weight: 600;
  }
  .intro {
    margin: var(--space-2) 0 var(--space-5);
    color: var(--text-secondary);
  }
  .card {
    position: relative;
    max-width: 40rem;
    padding: var(--space-5) var(--space-6);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    background: var(--surface);
    box-shadow: var(--shadow-card);
  }
  .card::before {
    content: '';
    position: absolute;
    top: 0;
    left: 0;
    width: 8px;
    height: 8px;
    border-top: 1px solid var(--border-strong);
    border-left: 1px solid var(--border-strong);
  }
  .live {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin: 0 0 var(--space-4);
    font-family: var(--font-mono);
    font-size: var(--text-xl);
    font-weight: 600;
  }
  dl {
    display: grid;
    grid-template-columns: max-content 1fr;
    gap: var(--space-3) var(--space-5);
    margin: 0;
  }
  dt {
    color: var(--text-muted);
    font-size: var(--text-2xs);
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .mono {
    font-family: var(--font-mono);
  }
  .full-hash {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }
  .help {
    display: block;
    color: var(--text-muted);
    font-size: var(--text-2xs);
  }
  .state {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    color: var(--text-secondary);
  }
  .retry {
    font: inherit;
    cursor: pointer;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-md);
    background: var(--surface);
    color: var(--accent-text);
    padding: var(--space-1) var(--space-2);
  }
</style>
