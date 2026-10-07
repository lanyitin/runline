<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { Firing, Trigger } from '../api/admin-model';
  import { createPolled } from '../api/polled.svelte';
  import { describeCron } from '../admin/cron';
  import SecretDialog from '../admin/SecretDialog.svelte';
  import WebhookUsage from '../admin/WebhookUsage.svelte';
  import { useApp } from '../app/context';
  import { pipelineHref, runHref, triggerEditHref } from '../app/links';
  import { enumLabel } from '../i18n/enums';
  import { browserClock, pageVisibility, type Clock, type Visibility } from '../runs/run-watch.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import ConfirmDialog from '../ui/ConfirmDialog.svelte';
  import CopyButton from '../ui/CopyButton.svelte';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // One trigger: what it runs (the version in full, the parameters it gives and the ones every run
  // gets), when (the expression, in words) or how to call it (the address, the headers and an
  // example), whether it is on, the way to rotate its secret, and its firings, newest first, with the
  // outcome, the reason and the run each made. The name is in the query: it may have a dot.
  interface Props {
    /** Where the time comes from; the tests give their own. */
    clock?: Clock;
    visibility?: Visibility;
  }
  let { clock = browserClock, visibility = pageVisibility }: Props = $props();

  const { i18n, api, router } = useApp();
  const name = new URLSearchParams(router.search).get('name') ?? '';
  const FIRINGS = 50;
  const FIRINGS_EVERY_MS = 5000;

  // The clock and the visibility of a page are the same for as long as it lives.
  // svelte-ignore state_referenced_locally
  const read = createPolled({
    load: () =>
      name === ''
        ? Promise.reject(new ApiFailure(404, { error: 'trigger_not_found' }))
        : api.trigger(name),
    clock,
    visibility,
    intervalMs: 0,
    auto: false,
  });
  // svelte-ignore state_referenced_locally
  const firingList = createPolled({
    load: () => api.firings(name, FIRINGS),
    clock,
    visibility,
    intervalMs: FIRINGS_EVERY_MS,
  });
  $effect(() => {
    void read.start();
    return () => read.dispose();
  });
  // The firings are asked for once the trigger is known to be there.
  $effect(() => {
    if (read.data === null) return;
    void firingList.start();
    return () => firingList.dispose();
  });

  const trigger = $derived<Trigger | null>(read.data);
  const firings = $derived<Firing[]>(firingList.data ?? []);
  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

  let switching = $state(false);
  let failure = $state.raw<ApiFailure | null>(null);
  async function toggle() {
    if (trigger === null || switching) return;
    switching = true;
    failure = null;
    try {
      await api.updateTrigger(trigger.name, { enabled: !trigger.enabled });
      read.reload();
      await read.settled();
    } catch (error) {
      failure = asFailure(error);
    } finally {
      switching = false;
    }
  }

  let deleting = $state(false);
  let deleteBusy = $state(false);
  let deleteFailure = $state.raw<ApiFailure | null>(null);
  async function confirmDelete() {
    if (deleteBusy) return;
    deleteBusy = true;
    deleteFailure = null;
    try {
      await api.deleteTrigger(name);
      router.navigate('/triggers');
    } catch (error) {
      deleteFailure = asFailure(error);
    } finally {
      deleteBusy = false;
    }
  }

  let rotating = $state(false);
  let rotateBusy = $state(false);
  let rotateFailure = $state.raw<ApiFailure | null>(null);
  // The new secret exists here, in this variable, from the answer of the Engine until the person
  // has said it is kept; then it is gone.
  let fresh = $state<{ secret: string; webhookPath: string } | null>(null);
  async function confirmRotate() {
    if (rotateBusy) return;
    rotateBusy = true;
    rotateFailure = null;
    try {
      const rotated = await api.rotateSecret(name);
      rotating = false;
      if (rotated.secret !== null && rotated.trigger.webhookPath !== null) {
        fresh = { secret: rotated.secret, webhookPath: rotated.trigger.webhookPath };
      }
      read.reload();
    } catch (error) {
      rotateFailure = asFailure(error);
    } finally {
      rotateBusy = false;
    }
  }

  const words = $derived(
    trigger?.cron ? describeCron(trigger.cron, i18n.translate, i18n.locale) : null,
  );
  const parameterNames = $derived(
    trigger === null
      ? []
      : [...new Set([...Object.keys(trigger.parameters), ...Object.keys(trigger.effectiveParameters)])].sort(),
  );
  const reasonText = (code: string) => {
    for (const key of [`firing.reason.${code}`, `error.${code}`]) {
      if (i18n.translate.has(key)) return i18n.translate(key);
    }
    return code;
  };
</script>

{#if read.status === 'failed' && read.error}
  <div class="rl-stack">
    <ApiErrorNotice failure={read.error} />
    {#if read.error.status !== 404}
      <div><button class="rl-btn retry" type="button" onclick={() => read.reload()}>{i18n.t('common.retry')}</button></div>
    {/if}
    <p><Link href="/triggers">{i18n.t('trigger.back')}</Link></p>
  </div>
{:else if trigger === null}
  <p class="muted" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
{:else}
  <p class="back"><Link href="/triggers">{i18n.t('trigger.back')}</Link></p>

  <div class="rl-page-head">
    <div>
      <h1><PlainText value={trigger.name} mono /></h1>
      <p class="sub">
        <span class="tag kind-{trigger.kind}">{enumLabel(i18n.translate, 'triggerKind', trigger.kind)}</span>
        <label class="switch">
          <input
            type="checkbox"
            role="switch"
            checked={trigger.enabled}
            disabled={switching}
            aria-label={i18n.t('triggers.enabled.label', { name: trigger.name })}
            onclick={(event) => {
              event.preventDefault();
              void toggle();
            }}
          />
          <span class="state">{trigger.enabled ? i18n.t('triggers.on') : i18n.t('triggers.off')}</span>
        </label>
      </p>
    </div>
    <div class="rl-actions">
      <Link href={triggerEditHref(trigger.name)} class="rl-btn">{i18n.t('triggers.edit')}</Link>
      {#if trigger.kind === 'webhook'}
        <button class="rl-btn" type="button" onclick={() => { rotating = true; rotateFailure = null; }}>
          {i18n.t('trigger.rotate')}
        </button>
      {/if}
      <button class="rl-btn danger" type="button" onclick={() => { deleting = true; deleteFailure = null; }}>
        {i18n.t('triggers.delete')}
      </button>
    </div>
  </div>

  {#if failure}
    <div class="problem"><ApiErrorNotice {failure} /></div>
  {/if}

  <div class="rl-stack">
    <section class="rl-card">
      <dl class="rl-dl facts">
        <dt>{i18n.t('trigger.createdBy')}</dt>
        <dd><PlainText value={trigger.createdBy} /> · <Timestamp iso={trigger.createdAt} /></dd>
        <dt>{i18n.t('trigger.updatedBy')}</dt>
        <dd><PlainText value={trigger.updatedBy} /> · <Timestamp iso={trigger.updatedAt} /></dd>
      </dl>
    </section>

    <section class="rl-card">
      <h2>{i18n.t('trigger.section.runs')}</h2>
      <dl class="rl-dl">
        <dt>{i18n.t('trigger.pipeline')}</dt>
        <dd><PlainText value={trigger.pipeline} mono /></dd>
        <dt>{i18n.t('trigger.uploader')}</dt>
        <dd><PlainText value={trigger.uploader} /></dd>
        <dt>{i18n.t('trigger.version')}</dt>
        <dd class="hash-line">
          <span class="rl-mono"><PlainText value={trigger.contentHash} mono /></span>
          <CopyButton
            text={trigger.contentHash}
            label={i18n.t('common.copyHash')}
            copied={i18n.t('common.copied')}
            failed={i18n.t('common.copyFailed')}
          />
          <Link href={pipelineHref(trigger.contentHash, trigger.pipeline, trigger.uploader)}>{i18n.t('trigger.viewPipeline')}</Link>
        </dd>
      </dl>
    </section>

    <section class="rl-card">
      <h2>{i18n.t('createRun.parameters')}</h2>
      {#if parameterNames.length === 0}
        <p>{i18n.t('trigger.params.none')}</p>
      {:else}
        <table class="rl-table">
          <thead>
            <tr>
              <th scope="col">{i18n.t('trigger.params.name')}</th>
              <th scope="col">{i18n.t('trigger.params.given')}</th>
              <th scope="col">{i18n.t('trigger.params.effective')}</th>
            </tr>
          </thead>
          <tbody>
            {#each parameterNames as parameter (parameter)}
              {@const given = trigger.parameters[parameter]}
              <tr>
                <td><PlainText value={parameter} mono /></td>
                <td>{#if given !== undefined}<PlainText value={given} mono />{/if}</td>
                <td>
                  <span class="value"><PlainText value={trigger.effectiveParameters[parameter] ?? ''} mono /></span>
                  {#if given === undefined}<span class="rl-help">{i18n.t('trigger.params.default')}</span>{/if}
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      {/if}
    </section>

    {#if trigger.cron !== null}
      <section class="rl-card">
        <h2>{i18n.t('trigger.section.schedule')}</h2>
        <dl class="rl-dl">
          <dt>{i18n.t('trigger.cron')}</dt>
          <dd><span class="cron rl-mono"><PlainText value={trigger.cron} mono /></span></dd>
          <dt>{i18n.t('trigger.timeZone')}</dt>
          <dd><PlainText value={trigger.timeZone ?? 'UTC'} mono /></dd>
          <dt>{i18n.t('trigger.meaning')}</dt>
          <dd>
            {#if words}<span class="words">{words}</span>{:else}<span class="rl-help">{i18n.t('trigger.noPreview')}</span>{/if}
          </dd>
        </dl>
      </section>
    {/if}

    {#if trigger.webhookPath !== null}
      <section class="rl-card">
        <h2>{i18n.t('trigger.section.webhook')}</h2>
        <div class="rl-stack">
          <WebhookUsage webhookPath={trigger.webhookPath} />
          <p class="rl-help">
            {#if trigger.secretRotatedAt}
              {i18n.t('trigger.secret.set')} <Timestamp iso={trigger.secretRotatedAt} />.
            {/if}
            {i18n.t('trigger.secret.hidden')}
          </p>
        </div>
      </section>
    {/if}

    <section class="rl-card">
      <h2>{i18n.t('trigger.section.firings')}</h2>
      {#if firingList.error}
        <ApiErrorNotice failure={firingList.error} />
      {/if}
      <div class="rl-toolbar">
        <label class="auto-label">
          <input
            type="checkbox"
            checked={firingList.auto}
            onchange={(event) => firingList.setAuto(event.currentTarget.checked)}
          />
          {i18n.t('firing.auto')}
        </label>
        <button class="rl-btn small refresh" type="button" onclick={() => firingList.reload()}>
          {i18n.t('firing.refresh')}
        </button>
      </div>
      {#if firingList.status === 'ready' && firings.length === 0}
        <p>{i18n.t('firing.empty')}</p>
      {:else if firings.length > 0}
        <div class="rl-table-wrap">
          <table class="rl-table">
            <thead>
              <tr>
                <th scope="col">{i18n.t('firing.col.time')}</th>
                <th scope="col">{trigger.kind === 'cron' ? i18n.t('firing.col.due') : i18n.t('firing.col.delivery')}</th>
                <th scope="col">{i18n.t('firing.col.outcome')}</th>
                <th scope="col">{i18n.t('firing.col.reason')}</th>
                <th scope="col">{i18n.t('firing.col.run')}</th>
              </tr>
            </thead>
            <tbody>
              {#each firings as item, index (`${item.firedAt}/${item.deliveryId ?? ''}/${index}`)}
                <tr>
                  <td class="rl-nowrap"><Timestamp iso={item.firedAt} /></td>
                  <td class="due rl-nowrap">
                    {#if item.scheduledFor}<Timestamp iso={item.scheduledFor} />{/if}
                    {#if item.deliveryId}<span class="delivery rl-mono"><PlainText value={item.deliveryId} mono /></span>{/if}
                  </td>
                  <td class="outcome"><span class="tag outcome-{item.outcome}">{enumLabel(i18n.translate, 'firingOutcome', item.outcome)}</span></td>
                  <td>
                    {#if item.reason}
                      <div class="reason"><PlainText value={reasonText(item.reason)} /></div>
                    {/if}
                    {#if item.detail}
                      <details class="detail">
                        <summary>{i18n.t('firing.detail')}</summary>
                        <PlainText value={item.detail} multiline />
                      </details>
                    {/if}
                  </td>
                  <td>
                    {#if item.runId}
                      <Link href={runHref(item.runId)} class="run rl-mono">{item.runId.slice(0, 8)}</Link>
                    {:else if item.outcome === 'run_created'}
                      <span class="run rl-help">{i18n.t('firing.runGone')}</span>
                    {:else}
                      <span class="rl-help">{i18n.t('firing.noRun')}</span>
                    {/if}
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
        <p class="rl-help">{i18n.t('firing.count', { count: firings.length })}</p>
      {/if}
    </section>
  </div>
{/if}

{#if deleting}
  <ConfirmDialog
    title={i18n.t('triggers.delete.title', { name })}
    confirmLabel={i18n.t('triggers.delete.confirm')}
    danger
    busy={deleteBusy}
    failure={deleteFailure}
    onconfirm={confirmDelete}
    oncancel={() => (deleting = false)}
  >
    <p>{i18n.t('triggers.delete.body')}</p>
  </ConfirmDialog>
{/if}

{#if rotating}
  <ConfirmDialog
    title={i18n.t('trigger.rotate.title', { name })}
    confirmLabel={i18n.t('trigger.rotate.confirm')}
    danger
    busy={rotateBusy}
    failure={rotateFailure}
    onconfirm={confirmRotate}
    oncancel={() => (rotating = false)}
  >
    <p>{i18n.t('trigger.rotate.body')}</p>
  </ConfirmDialog>
{/if}

{#if fresh}
  <SecretDialog {name} webhookPath={fresh.webhookPath} secret={fresh.secret} rotated onclose={() => (fresh = null)} />
{/if}

<style>
  .muted {
    color: var(--text-secondary);
  }
  .back {
    margin: 0 0 var(--space-3);
  }
  h1 {
    overflow-wrap: anywhere;
  }
  .problem {
    margin-bottom: var(--space-4);
  }
  .hash-line {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }
  .tag {
    padding: 2px var(--space-2);
    border-radius: var(--radius-pill);
    background: var(--neutral-tint);
    color: var(--neutral-text);
    font-size: var(--text-2xs);
    font-weight: 700;
  }
  .tag.kind-cron {
    background: var(--indigo-tint);
    color: var(--indigo-text);
  }
  .tag.kind-webhook {
    background: var(--accent-tint);
    color: var(--accent-text);
  }
  .tag.outcome-run_created {
    background: var(--success-tint);
    color: var(--success-text);
  }
  .tag.outcome-refused,
  .tag.outcome-interrupted {
    background: var(--warning-tint);
    color: var(--warning-text);
  }
  .tag.outcome-failed {
    background: var(--danger-tint);
    color: var(--danger-text);
  }
  .switch {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    margin-left: var(--space-3);
    cursor: pointer;
  }
  .state {
    font-weight: 600;
  }
  .words {
    color: var(--accent-text);
    font-weight: 600;
  }
  .auto-label {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
  }
  .detail {
    margin-top: var(--space-1);
    font-size: var(--text-xs);
    color: var(--text-secondary);
  }
  .delivery {
    margin-left: var(--space-2);
  }
</style>
