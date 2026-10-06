<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { Artifact } from '../api/model';
  import { useApp } from '../app/context';
  import { newRunHref, pipelineHref } from '../app/links';
  import { shortHash } from '../engine/info';
  import { formatBytes } from '../i18n/format';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Badge from '../ui/Badge.svelte';
  import Link from '../ui/Link.svelte';
  import Notice from '../ui/Notice.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // Puts a jar on the Engine (`POST /api/v1/artifacts`): choose or drop a file, send it with the
  // progress shown and the way to cancel, and say what came of it: a new version (201), the version
  // that was there already (200), or why it was refused (413, 422 with its code), in words, with the
  // Engine's own message (it names the entry or the class) as text.
  const { i18n, api } = useApp();

  let chosen = $state<File | null>(null);
  let phase = $state<'idle' | 'uploading' | 'done' | 'failed' | 'cancelled'>('idle');
  let sent = $state(0);
  let total = $state(0);
  let result = $state<{ created: boolean; artifact: Artifact } | null>(null);
  let failure = $state.raw<ApiFailure | null>(null);
  let over = $state(false);
  let abort: AbortController | null = null;

  const unsafe = $derived(result?.artifact.pipelines.some((p) => p.verdict === 'UNSAFE') ?? false);

  function take(file: File | undefined) {
    if (!file || phase === 'uploading') return;
    chosen = file;
    phase = 'idle';
    failure = null;
    result = null;
  }

  async function upload() {
    if (!chosen || phase === 'uploading') return;
    abort = new AbortController();
    phase = 'uploading';
    failure = null;
    sent = 0;
    total = chosen.size;
    try {
      result = await api.uploadJar(chosen, {
        signal: abort.signal,
        onProgress: (done, all) => {
          sent = done;
          total = all;
        },
      });
      phase = 'done';
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        phase = 'cancelled';
      } else {
        failure = error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));
        // A 401 is the end of the session: the Console shows the sign-in, not this page.
        phase = 'failed';
      }
    } finally {
      abort = null;
    }
  }

  function another() {
    chosen = null;
    result = null;
    failure = null;
    phase = 'idle';
  }
</script>

<div class="rl-page-head">
  <div>
    <h1>{i18n.t('page.upload.title')}</h1>
    <p class="sub">{i18n.t('page.upload.intro')}</p>
  </div>
</div>

{#if phase === 'done' && result}
  <section class="rl-card result" aria-labelledby="upload-result">
    <h2 id="upload-result" class="title">
      {result.created ? i18n.t('upload.created.title') : i18n.t('upload.existing.title')}
    </h2>
    <p>{result.created ? i18n.t('upload.created.body') : i18n.t('upload.existing.body')}</p>
    <dl class="rl-dl">
      <dt>{i18n.t('pipeline.version')}</dt>
      <dd><span class="hash rl-mono" title={result.artifact.contentHash}>{shortHash(result.artifact.contentHash)}</span></dd>
      <dt>{i18n.t('pipeline.size')}</dt>
      <dd>{formatBytes(result.artifact.sizeBytes, i18n.locale)}</dd>
      <dt>{i18n.t('pipeline.uploadedBy')}</dt>
      <dd><PlainText value={result.artifact.uploadedBy} /></dd>
      <dt>{i18n.t('pipeline.uploadedAt')}</dt>
      <dd><Timestamp iso={result.artifact.uploadedAt} /></dd>
    </dl>
    <p class="count">{i18n.t('upload.found', { count: result.artifact.pipelines.length })}</p>
    <ul class="pipelines">
      {#each result.artifact.pipelines as pipeline (pipeline.name)}
        <li class="pipeline-line">
          <span class="rl-mono"><PlainText value={pipeline.name} mono /></span>
          <Badge kind="verdict" value={pipeline.verdict} />
          <Link href={pipelineHref(result.artifact.contentHash, pipeline.name)}>{i18n.t('pipelines.details')}</Link>
          <Link href={newRunHref(result.artifact.contentHash, pipeline.name)}>{i18n.t('pipelines.run')}</Link>
        </li>
      {/each}
    </ul>
    {#if unsafe}
      <Notice tone="warning">{i18n.t('upload.unsafeFound')}</Notice>
    {/if}
    <div class="rl-actions">
      <button class="rl-btn another" type="button" onclick={another}>{i18n.t('upload.another')}</button>
    </div>
  </section>
{:else}
  <div class="rl-stack">
    <div
      class="dropzone"
      class:over
      role="group"
      aria-label={i18n.t('upload.drop')}
      ondragover={(event) => {
        event.preventDefault();
        over = true;
      }}
      ondragleave={() => (over = false)}
      ondrop={(event) => {
        event.preventDefault();
        over = false;
        take(event.dataTransfer?.files[0]);
      }}
    >
      <p>
        {i18n.t('upload.drop')}
        <label class="choose">
          <span class="rl-btn small">{i18n.t('upload.choose')}</span>
          <input
            class="sr-only"
            type="file"
            accept=".jar,application/java-archive,application/zip"
            disabled={phase === 'uploading'}
            onchange={(event) => take(event.currentTarget.files?.[0])}
          />
        </label>
      </p>
      <p class="rl-help">{i18n.t('upload.hint')}</p>
      {#if chosen}
        <p class="chosen">
          <span class="rl-label">{i18n.t('upload.selected')}</span>
          <PlainText value={chosen.name} mono />
          <span class="rl-muted">({formatBytes(chosen.size, i18n.locale)})</span>
        </p>
      {/if}
    </div>

    {#if phase === 'uploading'}
      <div role="status" class="progress">
        <progress max={total} value={sent}></progress>
        <span>{i18n.t('upload.progress', { sent: formatBytes(sent, i18n.locale), total: formatBytes(total, i18n.locale) })}</span>
      </div>
    {/if}
    {#if phase === 'cancelled'}
      <Notice tone="info">{i18n.t('upload.cancelled')}</Notice>
    {/if}
    {#if phase === 'failed' && failure}
      <ApiErrorNotice {failure} expandServerMessage />
    {/if}

    <div class="rl-actions">
      <button class="rl-btn primary submit" type="button" disabled={!chosen || phase === 'uploading'} onclick={upload}>
        {phase === 'uploading' ? i18n.t('upload.busy') : phase === 'failed' ? i18n.t('upload.tryAgain') : i18n.t('upload.submit')}
      </button>
      {#if phase === 'uploading'}
        <button class="rl-btn cancel" type="button" onclick={() => abort?.abort()}>{i18n.t('upload.cancel')}</button>
      {/if}
    </div>
  </div>
{/if}

<style>
  .dropzone {
    padding: var(--space-6);
    border: 1px dashed var(--border-strong);
    border-radius: var(--radius-lg);
    background-color: var(--surface);
    background-image: radial-gradient(var(--border) 1px, transparent 1px);
    background-size: 16px 16px;
    text-align: center;
  }
  .dropzone.over {
    border-color: var(--accent);
    background-color: var(--accent-tint);
  }
  .dropzone p {
    margin: 0 0 var(--space-2);
  }
  .choose {
    display: inline-block;
    cursor: pointer;
  }
  .choose:focus-within .rl-btn {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
  }
  .chosen {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    justify-content: center;
    gap: var(--space-2);
    margin-top: var(--space-3);
    overflow-wrap: anywhere;
  }
  .progress {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-3);
  }
  progress {
    flex: 1;
    min-width: 12rem;
    accent-color: var(--accent);
  }
  .title {
    margin: 0 0 var(--space-2);
    color: var(--text);
    font-size: var(--text-md);
    letter-spacing: 0;
    text-transform: none;
  }
  .count {
    margin: var(--space-4) 0 var(--space-2);
    font-weight: 600;
  }
  .pipelines {
    display: grid;
    gap: var(--space-2);
    margin: 0 0 var(--space-4);
    padding: 0;
    list-style: none;
  }
  .pipeline-line {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-3);
  }
</style>
