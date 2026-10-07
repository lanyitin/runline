<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { Trigger } from '../api/admin-model';
  import type { Artifact, Pipeline } from '../api/model';
  import { useApp } from '../app/context';
  import { newTriggerHref, triggerHref } from '../app/links';
  import { shortHash } from '../engine/info';
  import { enumLabel } from '../i18n/enums';
  import ConfirmDialog from '../ui/ConfirmDialog.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // What an admin does with one pipeline of one version, on its page: allow it to run although it is
  // UNSAFE (this version only; turning it on is confirmed, turning it off is not), bind a trigger to
  // it, and delete the version (confirmed; a version that a trigger or a run refers to is a 409
  // `in_use`, and the dialog says what refers to it).
  interface Props {
    artifact: Artifact;
    pipeline: Pipeline;
    /** The setting changed: the page reads the version again. */
    onchanged: () => void;
    /** The version is gone. */
    ondeleted: () => void;
  }
  let { artifact, pipeline, onchanged, ondeleted }: Props = $props();

  const { i18n, api } = useApp();
  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

  // ---- unsafe execution ------------------------------------------------------------------------
  let asking = $state(false);
  let busy = $state(false);
  let failure = $state.raw<ApiFailure | null>(null);
  let setBy = $state.raw<{ by: string; at: string } | null>(null);

  async function allow(value: boolean) {
    if (busy) return;
    busy = true;
    failure = null;
    try {
      const done = await api.setUnsafeExecution(
        artifact.contentHash,
        pipeline.name,
        value,
        artifact.uploader,
      );
      setBy = { by: done.setBy, at: done.setAt };
      asking = false;
      onchanged();
    } catch (error) {
      failure = asFailure(error);
    } finally {
      busy = false;
    }
  }

  // ---- deleting the version --------------------------------------------------------------------
  let deleting = $state(false);
  let deleteBusy = $state(false);
  let deleteFailure = $state.raw<ApiFailure | null>(null);
  let referrers = $state.raw<{ triggers: Trigger[]; runs: number } | null>(null);

  async function confirmDelete() {
    if (deleteBusy) return;
    deleteBusy = true;
    deleteFailure = null;
    referrers = null;
    try {
      await api.deleteVersion(artifact.contentHash, artifact.uploader);
      deleting = false;
      ondeleted();
    } catch (error) {
      deleteFailure = asFailure(error);
      if ((deleteFailure.body as { error?: string } | null)?.error === 'in_use') void explain();
    } finally {
      deleteBusy = false;
    }
  }

  /** What refers to the version, as far as an admin can see: the triggers, and the newest runs. */
  async function explain() {
    try {
      const [triggers, runs] = await Promise.all([api.triggers(), api.runs({ limit: 200 })]);
      referrers = {
        triggers: triggers.filter(
          (t) => t.contentHash === artifact.contentHash && t.uploader === artifact.uploader,
        ),
        runs: runs.filter(
          (r) => r.contentHash === artifact.contentHash && r.uploader === artifact.uploader,
        ).length,
      };
    } catch {
      // The refusal is already said; what refers to the version is a help, not a need.
    }
  }

  const allowed = $derived(pipeline.allowUnsafeExecution);
</script>

<section class="rl-card admin">
  <h2>{i18n.t('admin.title')}</h2>
  <div class="rl-stack">
    <div class="block">
      <h3>{i18n.t('admin.unsafe.title')}</h3>
      <label class="switch">
        <input
          type="checkbox"
          role="switch"
          checked={allowed}
          disabled={busy}
          aria-label={i18n.t('admin.unsafe.label')}
          onclick={(event) => {
            event.preventDefault();
            if (allowed) void allow(false);
            else asking = true;
          }}
        />
        <span class="state">{allowed ? i18n.t('admin.unsafe.state.allowed') : i18n.t('admin.unsafe.state.notAllowed')}</span>
      </label>
      {#if setBy}
        <span class="set-by rl-help">{i18n.t('admin.unsafe.setBy')} <PlainText value={setBy.by} /> <Timestamp iso={setBy.at} /></span>
      {/if}
      <p class="rl-help">{i18n.t('admin.unsafe.help')}</p>
      {#if pipeline.verdict === 'SAFE'}
        <p class="rl-help safe-note">{i18n.t('admin.unsafe.safeNote')}</p>
      {/if}
      {#if failure && !asking}
        <ApiErrorNotice {failure} />
      {/if}
    </div>

    <div class="block">
      <h3>{i18n.t('admin.trigger.bind')}</h3>
      <Link href={newTriggerHref(artifact.contentHash, pipeline.name, artifact.uploader)} class="rl-btn">{i18n.t('admin.trigger.bind')}</Link>
    </div>

    <div class="block">
      <h3>{i18n.t('admin.version.title')}</h3>
      <p class="rl-help">{i18n.t('admin.version.help')}</p>
      <div>
        <button class="rl-btn danger" type="button" onclick={() => { deleting = true; deleteFailure = null; referrers = null; }}>
          {i18n.t('admin.version.delete')}
        </button>
      </div>
    </div>
  </div>
</section>

{#if asking}
  <ConfirmDialog
    title={i18n.t('admin.unsafe.confirm.title', { name: pipeline.name })}
    confirmLabel={i18n.t('admin.unsafe.confirm.yes')}
    danger
    {busy}
    {failure}
    onconfirm={() => allow(true)}
    oncancel={() => { asking = false; failure = null; }}
  >
    <p>{i18n.t('admin.unsafe.confirm.body', { hash: shortHash(artifact.contentHash), count: pipeline.reasons.length })}</p>
    {#if pipeline.reasons.length > 0}
      <ul class="reasons">
        {#each pipeline.reasons as reason, index (index)}
          <li>
            {enumLabel(i18n.translate, 'reasonKind', reason.kind)}
            {#if reason.className}<PlainText value={reason.className} mono />{/if}
            {#if reason.member}<PlainText value={reason.member} mono />{/if}
          </li>
        {/each}
      </ul>
    {/if}
  </ConfirmDialog>
{/if}

{#if deleting}
  <ConfirmDialog
    title={i18n.t('admin.version.confirm.title', { hash: shortHash(artifact.contentHash) })}
    confirmLabel={i18n.t('admin.version.confirm.yes')}
    danger
    busy={deleteBusy}
    failure={deleteFailure}
    onconfirm={confirmDelete}
    oncancel={() => (deleting = false)}
  >
    <p>{i18n.t('admin.version.confirm.body', { count: artifact.pipelines.length })}</p>
    <ul class="reasons">
      {#each artifact.pipelines as other (other.name)}
        <li><PlainText value={other.name} mono /></li>
      {/each}
    </ul>
    {#if referrers}
      <div class="in-use rl-notice warning">
        <strong>{i18n.t('admin.version.inUse')}</strong>
        {#if referrers.triggers.length > 0}
          <p>
            {i18n.t('admin.version.inUse.triggers')}
            {#each referrers.triggers as trigger, index (trigger.name)}
              {#if index > 0},{/if}
              <Link href={triggerHref(trigger.name)}>{trigger.name}</Link>
            {/each}
          </p>
        {/if}
        {#if referrers.runs > 0}
          <p>{i18n.t('admin.version.inUse.runs', { count: referrers.runs })}</p>
        {/if}
      </div>
    {/if}
  </ConfirmDialog>
{/if}

<style>
  h3 {
    margin: 0 0 var(--space-2);
    font-size: var(--text-sm);
    font-weight: 600;
  }
  .block p {
    margin: var(--space-2) 0 0;
  }
  .switch {
    display: inline-flex;
    align-items: center;
    gap: var(--space-2);
    cursor: pointer;
  }
  .state {
    font-weight: 600;
  }
  .set-by {
    margin-left: var(--space-3);
  }
  .reasons {
    display: grid;
    gap: var(--space-1);
    margin: 0;
    padding-left: var(--space-5);
  }
  .in-use p {
    margin: var(--space-2) 0 0;
  }
</style>
