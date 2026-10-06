<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { Trigger } from '../api/admin-model';
  import { createPolled } from '../api/polled.svelte';
  import { describeCron } from '../admin/cron';
  import { useApp } from '../app/context';
  import { newTriggerHref, triggerEditHref, triggerHref } from '../app/links';
  import { shortHash } from '../engine/info';
  import { enumLabel } from '../i18n/enums';
  import { browserClock, pageVisibility, type Clock, type Visibility } from '../runs/run-watch.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import ConfirmDialog from '../ui/ConfirmDialog.svelte';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // The triggers (`GET /api/v1/triggers`), for admins: what each runs and when, a switch to turn it
  // on or off, and deleting it after a confirmation that says what follows. The page of one trigger
  // has the rest: its parameters, how to call a webhook, the secret and the firings.
  interface Props {
    /** Where the time comes from; the tests give their own. */
    clock?: Clock;
    visibility?: Visibility;
  }
  let { clock = browserClock, visibility = pageVisibility }: Props = $props();

  const { i18n, api } = useApp();

  // The clock and the visibility of a page are the same for as long as it lives.
  // svelte-ignore state_referenced_locally
  const list = createPolled({
    load: () => api.triggers(),
    clock,
    visibility,
    intervalMs: 0,
    auto: false,
  });
  $effect(() => {
    void list.start();
    return () => list.dispose();
  });

  const triggers = $derived<Trigger[]>(
    [...(list.data ?? [])].sort((a, b) => a.name.localeCompare(b.name)),
  );

  // What was asked of the Engine and is not done yet: the name of the trigger whose switch is moving.
  let switching = $state<string | null>(null);
  let failure = $state.raw<ApiFailure | null>(null);

  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

  async function toggle(trigger: Trigger) {
    if (switching !== null) return;
    switching = trigger.name;
    failure = null;
    try {
      await api.updateTrigger(trigger.name, { enabled: !trigger.enabled });
      list.reload();
      await list.settled();
    } catch (error) {
      failure = asFailure(error);
    } finally {
      switching = null;
    }
  }

  let deleting = $state<Trigger | null>(null);
  let deleteBusy = $state(false);
  let deleteFailure = $state.raw<ApiFailure | null>(null);

  async function confirmDelete() {
    if (deleting === null || deleteBusy) return;
    deleteBusy = true;
    deleteFailure = null;
    try {
      await api.deleteTrigger(deleting.name);
      deleting = null;
      list.reload();
    } catch (error) {
      deleteFailure = asFailure(error);
    } finally {
      deleteBusy = false;
    }
  }
  function askDelete(trigger: Trigger) {
    deleting = trigger;
    deleteFailure = null;
  }

  const words = (trigger: Trigger) =>
    trigger.cron === null ? null : describeCron(trigger.cron, i18n.translate, i18n.locale);
</script>

<div class="rl-page-head">
  <div>
    <h1>{i18n.t('page.triggers.title')}</h1>
    <p class="sub">{i18n.t('page.triggers.intro')}</p>
  </div>
  <div class="rl-actions">
    <Link href={newTriggerHref()} class="rl-btn primary">{i18n.t('triggers.new')}</Link>
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
  {#if failure}
    <div class="problem"><ApiErrorNotice {failure} /></div>
  {/if}

  {#if triggers.length === 0}
    <div class="rl-empty">
      <strong>{i18n.t('triggers.empty.title')}</strong>
      <p>{i18n.t('triggers.empty.body')}</p>
      <Link href={newTriggerHref()} class="rl-btn primary">{i18n.t('triggers.new')}</Link>
    </div>
  {:else}
    <div class="rl-table-wrap">
      <table class="rl-table">
        <thead>
          <tr>
            <th scope="col">{i18n.t('triggers.col.name')}</th>
            <th scope="col">{i18n.t('triggers.col.kind')}</th>
            <th scope="col">{i18n.t('triggers.col.target')}</th>
            <th scope="col">{i18n.t('triggers.col.schedule')}</th>
            <th scope="col">{i18n.t('triggers.col.enabled')}</th>
            <th scope="col">{i18n.t('triggers.col.updated')}</th>
            <th scope="col">{i18n.t('triggers.col.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {#each triggers as trigger (trigger.name)}
            {@const meaning = words(trigger)}
            <tr>
              <td class="name">
                <Link href={triggerHref(trigger.name)}><PlainText value={trigger.name} mono /></Link>
              </td>
              <td class="kind"><span class="tag kind-{trigger.kind}">{enumLabel(i18n.translate, 'triggerKind', trigger.kind)}</span></td>
              <td class="target">
                <div class="pipeline"><PlainText value={trigger.pipeline} mono /></div>
                <div class="version"><span class="hash rl-mono" title={trigger.contentHash}>{shortHash(trigger.contentHash)}</span></div>
              </td>
              <td class="schedule">
                {#if trigger.cron !== null}
                  <div><span class="cron rl-mono"><PlainText value={trigger.cron} mono /></span>
                    {#if trigger.timeZone}<span class="zone rl-help"><PlainText value={trigger.timeZone} /></span>{/if}
                  </div>
                  {#if meaning}<div class="words rl-help">{meaning}</div>{/if}
                {:else}
                  <span class="rl-help">{i18n.t('triggers.fromOutside')}</span>
                {/if}
              </td>
              <td class="enabled">
                <label class="switch">
                  <input
                    type="checkbox"
                    role="switch"
                    checked={trigger.enabled}
                    disabled={switching !== null}
                    aria-label={i18n.t('triggers.enabled.label', { name: trigger.name })}
                    onclick={(event) => {
                      event.preventDefault();
                      void toggle(trigger);
                    }}
                  />
                  <span class="state">{trigger.enabled ? i18n.t('triggers.on') : i18n.t('triggers.off')}</span>
                </label>
              </td>
              <td class="updated">
                <div><PlainText value={trigger.updatedBy} /></div>
                <div class="rl-help rl-nowrap"><Timestamp iso={trigger.updatedAt} /></div>
              </td>
              <td class="actions">
                <div class="rl-actions">
                  <Link href={triggerHref(trigger.name)} class="rl-btn small">{i18n.t('triggers.details')}</Link>
                  <Link href={triggerEditHref(trigger.name)} class="rl-btn small">{i18n.t('triggers.edit')}</Link>
                  <button class="rl-btn small danger" type="button" onclick={() => askDelete(trigger)}>
                    {i18n.t('triggers.delete')}
                  </button>
                </div>
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
    <p class="count rl-help">{i18n.t('triggers.count', { count: triggers.length })}</p>
  {/if}
{/if}

{#if deleting}
  <ConfirmDialog
    title={i18n.t('triggers.delete.title', { name: deleting.name })}
    confirmLabel={i18n.t('triggers.delete.confirm')}
    danger
    busy={deleteBusy}
    failure={deleteFailure}
    onconfirm={confirmDelete}
    oncancel={() => (deleting = null)}
  >
    <p>{i18n.t('triggers.delete.body')}</p>
  </ConfirmDialog>
{/if}

<style>
  .muted {
    color: var(--text-secondary);
  }
  .problem {
    margin-bottom: var(--space-4);
  }
  .pipeline {
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  .version {
    color: var(--text-muted);
    font-size: var(--text-xs);
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
  .zone {
    margin-left: var(--space-2);
  }
  .switch {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    cursor: pointer;
  }
  .state {
    min-width: 2.5em;
    font-weight: 600;
  }
  .count {
    margin-top: var(--space-3);
  }
</style>
