<script lang="ts">
  import { onMount } from 'svelte';
  import { ApiFailure } from '../api/failure';
  import type { DeletionImpact } from '../api/admin-model';
  import type { Artifact } from '../api/model';
  import { useApp } from '../app/context';
  import { triggerHref } from '../app/links';
  import { shortHash } from '../engine/info';
  import { enumLabel } from '../i18n/enums';
  import ConfirmDialog from '../ui/ConfirmDialog.svelte';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // Deleting a version, after the Engine has said what it would take along (ADR-047). The numbers
  // are the Engine's preview and nothing is worked out here. Confirming sends the number of runs
  // that was shown; if the Engine finds something else by then, it refuses, and the dialog shows
  // the impact as it is now and waits for a new answer.
  interface Props {
    artifact: Artifact;
    /** The version is gone. */
    ondeleted: () => void;
    oncancel: () => void;
  }
  let { artifact, ondeleted, oncancel }: Props = $props();

  const { i18n, api } = useApp();
  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));
  /** The refusals that say the impact is not what the person saw: it is read again. */
  const CHANGED = ['impact_changed', 'runs_active', 'runs_exist', 'in_use'];

  let impact = $state.raw<DeletionImpact | null>(null);
  let loading = $state(true);
  let readFailure = $state.raw<ApiFailure | null>(null);
  let deleteFailure = $state.raw<ApiFailure | null>(null);
  let busy = $state(false);

  async function read() {
    loading = true;
    readFailure = null;
    try {
      impact = await api.deletionImpact(artifact.contentHash, artifact.uploader);
    } catch (error) {
      impact = null;
      readFailure = asFailure(error);
    } finally {
      loading = false;
    }
  }
  onMount(() => void read());

  async function confirm() {
    if (busy || loading || !impact?.deletable) return;
    busy = true;
    deleteFailure = null;
    try {
      await api.deleteVersion(artifact.contentHash, artifact.uploader, impact.runs.total);
      ondeleted();
    } catch (error) {
      deleteFailure = asFailure(error);
      const code = (deleteFailure.body as { error?: string } | null)?.error;
      if (code && CHANGED.includes(code)) await read();
    } finally {
      busy = false;
    }
  }
</script>

<ConfirmDialog
  title={i18n.t('admin.version.confirm.title', { hash: shortHash(artifact.contentHash) })}
  confirmLabel={i18n.t('admin.version.confirm.yes')}
  danger
  {busy}
  confirmDisabled={loading || !impact?.deletable}
  failure={readFailure ?? deleteFailure}
  onconfirm={confirm}
  {oncancel}
>
  <p>{i18n.t('admin.version.confirm.body', { count: artifact.pipelines.length })}</p>
  <ul class="pipelines">
    {#each artifact.pipelines as other (other.name)}
      <li><PlainText value={other.name} mono /></li>
    {/each}
  </ul>
  {#if loading && !impact}
    <p class="rl-help" role="status">{i18n.t('common.loading')}</p>
  {/if}
  {#if impact}
    <div class="impact">
      {#if impact.runs.total > 0}
        <p>
          <strong>{i18n.t('admin.version.impact.runs', { count: impact.runs.total })}</strong>,
          {i18n.t('admin.version.impact.logs', { count: impact.logEntries })}
        </p>
        <ul class="states">
          {#each Object.entries(impact.runs.byState) as [state, count] (state)}
            <li>{enumLabel(i18n.translate, 'runState', state)}: {i18n.t('admin.version.impact.count', { count })}</li>
          {/each}
        </ul>
        {#if impact.runs.earliestAt && impact.runs.latestAt}
          <p class="rl-help">
            {i18n.t('admin.version.impact.range')}
            <Timestamp iso={impact.runs.earliestAt} /> – <Timestamp iso={impact.runs.latestAt} />
          </p>
        {/if}
      {/if}
      <p class="rl-help">
        {impact.contentRemoved ? i18n.t('admin.version.impact.jarRemoved') : i18n.t('admin.version.impact.jarStays')}
      </p>
    </div>
    {#if impact.deletable}
      <p class="effect">
        {impact.runs.total > 0 ? i18n.t('admin.version.effect.withRuns') : i18n.t('admin.version.effect.noRuns')}
      </p>
    {:else}
      <div class="blocked rl-notice warning">
        <strong>{i18n.t('admin.version.blocked')}</strong>
        {#if impact.triggers.length > 0}
          <p>
            {i18n.t('admin.version.blocked.triggers')}
            {#each impact.triggers as trigger, index (trigger.name)}
              {#if index > 0},{/if}
              <Link href={triggerHref(trigger.name)}><PlainText value={trigger.name} /></Link>
            {/each}
          </p>
        {/if}
        {#if impact.runs.active > 0}
          <p>{i18n.t('admin.version.blocked.active', { count: impact.runs.active })}</p>
        {/if}
      </div>
    {/if}
  {/if}
</ConfirmDialog>

<style>
  ul {
    display: grid;
    gap: var(--space-1);
    margin: 0;
    padding-left: var(--space-5);
  }
  .impact,
  .blocked,
  .effect {
    margin-top: var(--space-3);
  }
  .impact p,
  .blocked p {
    margin: var(--space-2) 0 0;
  }
</style>
