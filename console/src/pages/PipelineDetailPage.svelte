<script lang="ts">
  import { createPolled } from '../api/polled.svelte';
  import type { Pipeline } from '../api/model';
  import { useApp } from '../app/context';
  import { newRunHref, pipelineHref } from '../app/links';
  import { enumLabel } from '../i18n/enums';
  import { formatBytes } from '../i18n/format';
  import { browserClock, pageVisibility } from '../runs/run-watch.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Badge from '../ui/Badge.svelte';
  import CopyButton from '../ui/CopyButton.svelte';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // One version of a jar and one pipeline in it (`GET /api/v1/artifacts/{hash}`): what it is, what
  // it declares (parameters, files, network, processes, shared resources), the verdict and every
  // reason for it with the path from the pipeline to the reference, the warnings, and what the
  // verdict does not cover. Everything the jar says is text.
  interface Props {
    contentHash: string;
  }
  let { contentHash }: Props = $props();

  const { i18n, api, router } = useApp();

  const version = createPolled({
    load: () => api.artifact(contentHash),
    clock: browserClock,
    visibility: pageVisibility,
    intervalMs: 0,
    auto: false,
  });
  $effect(() => {
    void version.start();
    return () => version.dispose();
  });

  const artifact = $derived(version.data);
  const wanted = $derived(new URLSearchParams(router.search).get('pipeline'));
  const pipeline = $derived<Pipeline | null>(
    artifact === null ? null : wanted === null ? (artifact.pipelines[0] ?? null) : (artifact.pipelines.find((p) => p.name === wanted) ?? null),
  );
  const notFound = $derived(version.status === 'failed' && version.error?.status === 404);

  const label = (key: string, fallback: string) =>
    i18n.translate.has(key) ? i18n.translate(key) : fallback;
  const warningText = (kind: string, resource: string, message: string) =>
    i18n.translate.has(`warning.${kind}`) ? i18n.translate(`warning.${kind}`, { resource }) : message;
</script>

{#if notFound}
  <div class="rl-notice warning" role="status">
    <strong>{i18n.t('pipeline.notFound.title')}</strong>
    <p>{i18n.t('pipeline.notFound.body')}</p>
  </div>
  <p><Link href="/pipelines">{i18n.t('pipeline.back')}</Link></p>
{:else if version.status === 'failed' && version.error}
  <div class="rl-stack">
    <ApiErrorNotice failure={version.error} />
    <div><button class="rl-btn retry" type="button" onclick={() => version.reload()}>{i18n.t('common.retry')}</button></div>
  </div>
{:else if artifact === null}
  <p class="muted" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
{:else}
  <p class="back"><Link href="/pipelines">{i18n.t('pipeline.back')}</Link></p>

  {#if pipeline === null}
    <div class="rl-notice warning" role="status">
      {i18n.t('pipeline.noSuchPipeline', { name: wanted ?? '' })}
    </div>
  {:else}
    <div class="rl-page-head">
      <div>
        <h1><PlainText value={pipeline.name} mono /></h1>
        <p class="sub"><PlainText value={pipeline.className} mono /></p>
      </div>
      <div class="rl-actions">
        <Badge kind="verdict" value={pipeline.verdict} />
        <Link href={newRunHref(artifact.contentHash, pipeline.name)} class="rl-btn primary">
          {i18n.t('pipeline.createRun')}
        </Link>
      </div>
    </div>

    <div class="rl-stack">
      <section class="rl-card">
        <dl class="rl-dl facts">
          <dt>{i18n.t('pipeline.version')}</dt>
          <dd class="hash-line">
            <span class="rl-mono"><PlainText value={artifact.contentHash} mono /></span>
            <CopyButton
              text={artifact.contentHash}
              label={i18n.t('common.copyHash')}
              copied={i18n.t('common.copied')}
              failed={i18n.t('common.copyFailed')}
            />
          </dd>
          <dt>{i18n.t('pipeline.size')}</dt>
          <dd>{formatBytes(artifact.sizeBytes, i18n.locale)}</dd>
          <dt>{i18n.t('pipeline.uploadedBy')}</dt>
          <dd><PlainText value={artifact.uploadedBy} /></dd>
          <dt>{i18n.t('pipeline.uploadedAt')}</dt>
          <dd><Timestamp iso={artifact.uploadedAt} /></dd>
          <dt>{i18n.t('pipeline.allowListVersion')}</dt>
          <dd><PlainText value={pipeline.allowListVersion} mono /></dd>
        </dl>
        {#if artifact.pipelines.length > 1}
          <div class="others">
            <span class="rl-label">{i18n.t('pipeline.others')}</span>
            <ul>
              {#each artifact.pipelines as other (other.name)}
                <li>
                  <Link href={pipelineHref(artifact.contentHash, other.name)}>
                    <PlainText value={other.name} mono />
                  </Link>
                  <Badge kind="verdict" value={other.verdict} />
                </li>
              {/each}
            </ul>
          </div>
        {/if}
      </section>

      <h2 class="group">{i18n.t('pipeline.section.metadata')}</h2>

      <section class="rl-card">
        <h2>{i18n.t('pipeline.params')}</h2>
        {#if pipeline.metadata.parameters.length === 0}
          <p>{i18n.t('pipeline.params.none')}</p>
        {:else}
          <table class="rl-table">
            <thead>
              <tr>
                <th scope="col">{i18n.t('pipeline.param.name')}</th>
                <th scope="col">{i18n.t('pipeline.param.required')}</th>
                <th scope="col">{i18n.t('pipeline.param.default')}</th>
              </tr>
            </thead>
            <tbody>
              {#each pipeline.metadata.parameters as parameter (parameter.name)}
                <tr>
                  <td><PlainText value={parameter.name} mono /></td>
                  <td>{parameter.required ? i18n.t('pipeline.param.required') : i18n.t('pipeline.param.optional')}</td>
                  <td>{#if parameter.default !== null}<PlainText value={parameter.default} mono />{/if}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        {/if}
      </section>

      <section class="rl-card">
        <h2>{i18n.t('pipeline.files')}</h2>
        {#if pipeline.metadata.files.length === 0}
          <p>{i18n.t('pipeline.files.none')}</p>
        {:else}
          <ul class="plain-list">
            {#each pipeline.metadata.files as file (file.scope)}
              <li>
                {label(`pipeline.scope.${file.scope}`, file.scope)}:
                {label(`pipeline.mode.${file.mode}`, file.mode)}
              </li>
            {/each}
          </ul>
        {/if}
      </section>

      {#each [['network', pipeline.metadata.network], ['processes', pipeline.metadata.processes]] as const as [which, access] (which)}
        <section class="rl-card">
          <h2>{i18n.t(which === 'network' ? 'pipeline.network' : 'pipeline.processes')}</h2>
          {#if access.unrestricted}
            <p class="unrestricted">{i18n.t('pipeline.access.unrestricted')}</p>
          {:else if access.allow.length === 0}
            <p>{i18n.t('pipeline.access.none')}</p>
          {:else}
            <p>{i18n.t('pipeline.access.only')}</p>
            <ul class="plain-list">
              {#each access.allow as entry (entry)}
                <li><PlainText value={entry} mono /></li>
              {/each}
            </ul>
          {/if}
        </section>
      {/each}

      <section class="rl-card">
        <h2>{i18n.t('pipeline.resources')}</h2>
        {#if pipeline.metadata.resources.length === 0}
          <p>{i18n.t('pipeline.resources.none')}</p>
        {:else}
          <ul class="plain-list">
            {#each pipeline.metadata.resources as resource (resource)}
              <li><PlainText value={resource} mono /></li>
            {/each}
          </ul>
        {/if}
      </section>

      <section class="rl-card">
        <h2>{i18n.t('pipeline.section.verdict')}</h2>
        {#if pipeline.verdict === 'UNSAFE'}
          <div class="rl-notice {pipeline.allowUnsafeExecution ? 'warning' : 'danger'}" role="status">
            {pipeline.allowUnsafeExecution ? i18n.t('pipeline.unsafeAllowed') : i18n.t('pipeline.unsafeNotAllowed')}
          </div>
        {/if}
        {#if pipeline.reasons.length === 0}
          {#if pipeline.verdict === 'SAFE'}
            <p>{i18n.t('pipeline.safe')}</p>
          {/if}
        {:else}
          <h3>{i18n.t('pipeline.reasons')}</h3>
          <ul class="reasons">
            {#each pipeline.reasons as reason, index (index)}
              <li>
                <strong>{enumLabel(i18n.translate, 'reasonKind', reason.kind)}</strong>
                <dl class="rl-dl">
                  {#if reason.category}
                    <dt>{i18n.t('pipeline.reason.category')}</dt>
                    <dd>{label(`reasonCategory.${reason.category}`, reason.category)}</dd>
                  {/if}
                  {#if reason.className}
                    <dt>{i18n.t('pipeline.reason.class')}</dt>
                    <dd><PlainText value={reason.className} mono /></dd>
                  {/if}
                  {#if reason.member}
                    <dt>{i18n.t('pipeline.reason.member')}</dt>
                    <dd><PlainText value={reason.member} mono /></dd>
                  {/if}
                  {#if reason.detail}
                    <dt>{i18n.t('pipeline.reason.detail')}</dt>
                    <dd><PlainText value={reason.detail} /></dd>
                  {/if}
                  {#if reason.path.length > 0}
                    <dt>{i18n.t('pipeline.reason.path')}</dt>
                    <dd>
                      <ol class="path">
                        {#each reason.path as step, position (position)}
                          <li><PlainText value={step} mono /></li>
                        {/each}
                      </ol>
                    </dd>
                  {/if}
                </dl>
              </li>
            {/each}
          </ul>
        {/if}
      </section>

      {#if pipeline.warnings.length > 0}
        <section class="rl-card">
          <h2>{i18n.t('pipeline.warnings')}</h2>
          <ul class="plain-list">
            {#each pipeline.warnings as warning, index (index)}
              <li class="warning-line"><PlainText value={warningText(warning.kind, warning.resource, warning.message)} /></li>
            {/each}
          </ul>
        </section>
      {/if}

      {#if artifact.limitations}
        <p class="limitations rl-help">
          <strong>{i18n.t('pipeline.limitations')}</strong>
          <PlainText value={artifact.limitations} multiline />
        </p>
      {/if}
    </div>
  {/if}
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
  .group {
    margin-top: var(--space-2);
    color: var(--text-muted);
    font-size: var(--text-xs);
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  h3 {
    margin: var(--space-3) 0 var(--space-2);
    font-size: var(--text-sm);
    font-weight: 600;
  }
  .hash-line {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-2);
  }
  .others {
    margin-top: var(--space-4);
  }
  .others ul,
  .plain-list {
    display: grid;
    gap: var(--space-2);
    margin: var(--space-2) 0 0;
    padding: 0;
    list-style: none;
  }
  .others li {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
  .unrestricted {
    color: var(--warning-text);
    font-weight: 600;
  }
  .reasons {
    display: grid;
    gap: var(--space-4);
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .reasons > li {
    padding: var(--space-3) var(--space-4);
    border: 1px solid var(--border);
    border-left: 3px solid var(--danger);
    border-radius: var(--radius-md);
    background: var(--surface-subtle);
  }
  .reasons .rl-dl {
    margin-top: var(--space-2);
  }
  /* The path from the pipeline to the reference, as a trail: each step leads to the next. */
  .path {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: var(--space-1);
    margin: 0;
    padding: 0;
    list-style: none;
  }
  .path li + li::before {
    content: '›';
    margin-right: var(--space-1);
    color: var(--text-muted);
  }
  .warning-line {
    color: var(--warning-text);
  }
  .limitations {
    margin: 0;
  }
  .limitations strong {
    display: block;
  }
</style>
