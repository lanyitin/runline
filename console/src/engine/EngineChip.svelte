<script lang="ts">
  import { tick } from 'svelte';
  import { useApp } from '../app/context';
  import { formatDuration } from '../i18n/format';
  import CopyButton from '../ui/CopyButton.svelte';
  import StatusDot from '../ui/StatusDot.svelte';
  import Timestamp from '../ui/Timestamp.svelte';
  import { shortHash } from './info';

  // The Engine's version and commit hash, in every layout of the Console, signed in or not (the hard
  // requirement of the design system). Clicking it opens the details: the full hash with a copy
  // button, and the address of the API. The build time, JDK, uptime and allow-list version belong to
  // the signed-in `GET /api/v1/system`: before anyone has signed in they are not there. The dot is
  // green while the last call to the Engine got an answer and red when it did not (no probe of its
  // own), and the words say it too.
  interface Props {
    /** Where the chip is: only the way the details open depends on it. */
    placement?: 'sidebar' | 'topbar' | 'footer';
  }
  let { placement = 'topbar' }: Props = $props();

  const { i18n, engineInfo, connection } = useApp();
  const id = $props.id();

  let open = $state(false);
  let toggle: HTMLButtonElement | undefined = $state();
  let close: HTMLButtonElement | undefined = $state();

  async function show() {
    open = true;
    await tick();
    close?.focus();
  }

  function hide() {
    open = false;
    toggle?.focus();
  }

  const current = $derived(engineInfo.state);
  const down = $derived(connection.state === 'down');

  // How long the Engine has been up now: what it said, and the time since it said it.
  const uptime = (system: { uptimeSeconds: number; receivedAt: number }) =>
    formatDuration(system.uptimeSeconds * 1000 + Math.max(0, Date.now() - system.receivedAt), i18n.locale);
</script>

<div
  class="chip {placement}"
  role="presentation"
  onkeydown={(event) => {
    if (event.key === 'Escape' && open) hide();
  }}
>
  {#if current.status === 'loading'}
    <span class="plain-state" role="status" aria-busy="true">
      <StatusDot tone="neutral" />
      {i18n.t('engine.loading')}
    </span>
  {:else if current.status === 'failed'}
    <span class="plain-state failed">
      <StatusDot tone="danger" />
      {i18n.t('engine.unavailable')}
      <button class="retry" type="button" onclick={() => engineInfo.reload()}>
        {i18n.t('engine.retry')}
      </button>
    </span>
  {:else}
    {@const info = current.info}
    <button
      class="toggle"
      type="button"
      bind:this={toggle}
      aria-expanded={open}
      aria-controls="{id}-details"
      onclick={() => (open ? hide() : show())}
    >
      <StatusDot tone={down ? 'danger' : 'success'} glow={!down} />
      <span class="version">v{info.version}</span>
      <span class="hash mono">
        {info.commitHash === 'unknown' ? i18n.t('engine.unknown') : shortHash(info.commitHash)}
      </span>
      {#if info.dirty}
        <span class="dirty" title={i18n.t('engine.dirty')}>{i18n.t('engine.dirtyMark')}</span>
      {/if}
      {#if down}
        <span class="down">{i18n.t('engine.disconnected')}</span>
      {/if}
    </button>

    {#if open}
      <div
        class="details"
        id="{id}-details"
        role="dialog"
        aria-label={i18n.t('engine.details')}
      >
        <h2>{i18n.t('engine.details')}</h2>
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
          {#if info.system}
            <dt>{i18n.t('engine.buildTime')}</dt>
            <dd>
              <Timestamp iso={info.system.buildTime} />
              <span class="help">{i18n.t('engine.buildTimeHelp')}</span>
            </dd>
            <dt>{i18n.t('engine.jdk')}</dt>
            <dd class="mono">{info.system.jdk}</dd>
            <dt>{i18n.t('engine.uptime')}</dt>
            <dd class="mono">{uptime(info.system)}</dd>
            <dt>{i18n.t('engine.allowList')}</dt>
            <dd class="mono">{info.system.allowListVersion}</dd>
          {/if}
          <dt>{i18n.t('engine.apiBase')}</dt>
          <dd class="mono">{location.origin}</dd>
        </dl>
        <button class="close" type="button" bind:this={close} onclick={hide}>
          {i18n.t('engine.close')}
        </button>
      </div>
    {/if}
  {/if}
</div>

<style>
  .chip {
    position: relative;
    display: inline-block;
    font-size: var(--text-xs);
  }
  .toggle,
  .plain-state {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-1) var(--space-3);
    border: 1px solid var(--border);
    border-radius: var(--radius-pill);
    background: var(--surface);
    color: var(--text);
    font: inherit;
    font-weight: 600;
  }
  .toggle {
    cursor: pointer;
  }
  .toggle:hover {
    border-color: var(--border-strong);
  }
  .topbar .toggle {
    background: var(--accent-tint);
    border-color: var(--accent-line);
    color: var(--accent-text);
  }
  .chip.sidebar {
    display: block;
  }
  .sidebar .toggle,
  .sidebar .plain-state {
    width: 100%;
    flex-wrap: wrap;
    border-radius: var(--radius-lg);
    background: var(--surface-subtle);
    padding: var(--space-3);
  }
  .version {
    white-space: nowrap;
  }
  .hash {
    color: var(--text-muted);
    font-weight: 500;
  }
  .topbar .hash {
    color: inherit;
  }
  .dirty {
    border-radius: var(--radius-pill);
    padding: 0 var(--space-2);
    background: var(--warning-tint);
    color: var(--warning-text);
    font-weight: 600;
  }
  .down {
    border-radius: var(--radius-pill);
    padding: 0 var(--space-2);
    background: var(--danger-tint);
    color: var(--danger-text);
    font-weight: 600;
  }
  .help {
    display: block;
    color: var(--text-muted);
    font-size: var(--text-2xs);
  }
  .retry,
  .close {
    font: inherit;
    cursor: pointer;
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-md);
    background: var(--surface);
    color: var(--accent-text);
    padding: var(--space-1) var(--space-2);
  }
  .details {
    position: absolute;
    z-index: 50;
    width: 20rem;
    padding: var(--space-4);
    border: 1px solid var(--border-strong);
    border-radius: var(--radius-lg);
    background: var(--surface);
    box-shadow: var(--shadow-popover);
    color: var(--text);
  }
  .topbar .details {
    top: calc(100% + var(--space-2));
    right: 0;
  }
  .sidebar .details {
    bottom: calc(100% + var(--space-2));
    left: 0;
  }
  .footer .details {
    bottom: calc(100% + var(--space-2));
    left: 50%;
    transform: translateX(-50%);
  }
  h2 {
    margin: 0 0 var(--space-3);
    font-size: var(--text-xs);
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--text-muted);
  }
  dl {
    margin: 0 0 var(--space-3);
  }
  dt {
    margin-top: var(--space-2);
    font-size: var(--text-2xs);
    color: var(--text-muted);
  }
  dd {
    margin: 0;
    overflow-wrap: anywhere;
  }
  .full-hash {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    justify-content: space-between;
  }
  .mono {
    font-family: var(--font-mono);
  }
</style>
