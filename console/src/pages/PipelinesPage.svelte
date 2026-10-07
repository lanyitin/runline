<script lang="ts">
  import { createPolled } from '../api/polled.svelte';
  import type { Definition } from '../api/model';
  import { useApp } from '../app/context';
  import { newRunHref, pipelineHref } from '../app/links';
  import { shortHash } from '../engine/info';
  import { browserClock, pageVisibility } from '../runs/run-watch.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Badge from '../ui/Badge.svelte';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';
  import Timestamp from '../ui/Timestamp.svelte';

  // The pipelines of the jars the caller may see (`GET /api/v1/definitions`), newest version first.
  // What the caller may see is the Engine's decision: nothing is filtered here but what the person
  // asks to filter. The names, classes and uploaders are text, whatever they say.
  const { i18n, api } = useApp();
  const PAGE_SIZE = 25;

  const list = createPolled({
    load: () => api.definitions(),
    clock: browserClock,
    visibility: pageVisibility,
    intervalMs: 0,
    auto: false,
  });
  $effect(() => {
    void list.start();
    return () => list.dispose();
  });

  let verdict = $state('');
  let uploader = $state('');
  let search = $state('');
  let pageIndex = $state(0);

  const all = $derived<Definition[]>(
    [...(list.data?.definitions ?? [])].sort(
      (a, b) => b.uploadedAt.localeCompare(a.uploadedAt) || a.name.localeCompare(b.name),
    ),
  );
  const uploaders = $derived([...new Set(all.map((d) => d.uploadedBy))].sort());
  const matching = $derived.by(() => {
    const word = search.trim().toLowerCase();
    return all.filter(
      (d) =>
        (verdict === '' || d.verdict === verdict) &&
        (uploader === '' || d.uploadedBy === uploader) &&
        (word === '' || d.name.toLowerCase().includes(word) || d.className.toLowerCase().includes(word)),
    );
  });
  const pages = $derived(Math.max(1, Math.ceil(matching.length / PAGE_SIZE)));
  const shown = $derived(matching.slice(pageIndex * PAGE_SIZE, (pageIndex + 1) * PAGE_SIZE));

  // A new filter starts at the first page; a list that got shorter never leaves an empty page.
  $effect(() => {
    void [verdict, uploader, search];
    pageIndex = 0;
  });
  $effect(() => {
    if (pageIndex > pages - 1) pageIndex = pages - 1;
  });
</script>

<div class="rl-page-head">
  <div>
    <h1>{i18n.t('page.pipelines.title')}</h1>
    <p class="sub">{i18n.t('page.pipelines.intro')}</p>
  </div>
  <div class="rl-actions">
    <Link href="/upload" class="rl-btn primary">{i18n.t('pipelines.upload')}</Link>
  </div>
</div>

{#if list.status === 'failed' && list.error}
  <div class="rl-stack">
    <ApiErrorNotice failure={list.error} />
    <div><button class="rl-btn retry" type="button" onclick={() => list.reload()}>{i18n.t('common.retry')}</button></div>
  </div>
{:else if list.status === 'loading'}
  <p class="muted" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
{:else if all.length === 0}
  <div class="rl-empty">
    <strong>{i18n.t('pipelines.empty.title')}</strong>
    <p>{i18n.t('pipelines.empty.body')}</p>
    <Link href="/upload" class="rl-btn primary">{i18n.t('pipelines.upload')}</Link>
  </div>
{:else}
  <div class="rl-toolbar">
    <div class="rl-field grow">
      <label for="pipelines-search">{i18n.t('pipelines.filter.search')}</label>
      <input id="pipelines-search" class="rl-input" type="search" bind:value={search} />
    </div>
    <div class="rl-field">
      <label for="pipelines-verdict">{i18n.t('pipelines.filter.verdict')}</label>
      <select id="pipelines-verdict" class="rl-input" bind:value={verdict}>
        <option value="">{i18n.t('pipelines.filter.all')}</option>
        <option value="SAFE">{i18n.t('verdict.SAFE')}</option>
        <option value="UNSAFE">{i18n.t('verdict.UNSAFE')}</option>
      </select>
    </div>
    <div class="rl-field">
      <label for="pipelines-uploader">{i18n.t('pipelines.filter.uploader')}</label>
      <select id="pipelines-uploader" class="rl-input" bind:value={uploader}>
        <option value="">{i18n.t('pipelines.filter.allUploaders')}</option>
        {#each uploaders as name (name)}
          <option value={name}>{name}</option>
        {/each}
      </select>
    </div>
  </div>

  {#if matching.length === 0}
    <p class="rl-empty">{i18n.t('pipelines.noMatch')}</p>
  {:else}
    <div class="rl-table-wrap">
      <table class="rl-table">
        <thead>
          <tr>
            <th scope="col">{i18n.t('pipelines.col.name')}</th>
            <th scope="col">{i18n.t('pipelines.col.verdict')}</th>
            <th scope="col">{i18n.t('pipelines.col.version')}</th>
            <th scope="col">{i18n.t('pipelines.col.uploader')}</th>
            <th scope="col">{i18n.t('pipelines.col.uploadedAt')}</th>
            <th scope="col">{i18n.t('pipelines.col.allowList')}</th>
            <th scope="col">{i18n.t('pipelines.col.actions')}</th>
          </tr>
        </thead>
        <tbody>
          {#each shown as definition (JSON.stringify([definition.contentHash, definition.uploader, definition.name]))}
            <tr>
              <td>
                <div class="name">
                  <Link href={pipelineHref(definition.contentHash, definition.name, definition.uploader)}>
                    <PlainText value={definition.name} mono />
                  </Link>
                </div>
                <div class="class"><PlainText value={definition.className} mono /></div>
              </td>
              <td><Badge kind="verdict" value={definition.verdict} /></td>
              <td><span class="hash rl-mono" title={definition.contentHash}>{shortHash(definition.contentHash)}</span></td>
              <td><PlainText value={definition.uploadedBy} /></td>
              <td class="rl-nowrap"><Timestamp iso={definition.uploadedAt} /></td>
              <td><span class="allow-list rl-mono"><PlainText value={definition.allowListVersion} /></span></td>
              <td class="rl-nowrap">
                <Link href={pipelineHref(definition.contentHash, definition.name, definition.uploader)}>{i18n.t('pipelines.details')}</Link>
                ·
                <Link href={newRunHref(definition.contentHash, definition.name, {}, definition.uploader)}>{i18n.t('pipelines.run')}</Link>
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
    <div class="pager">
      <span>{i18n.t('common.showing', { from: pageIndex * PAGE_SIZE + 1, to: pageIndex * PAGE_SIZE + shown.length, total: matching.length })}</span>
      <span class="rl-actions">
        <button class="rl-btn small prev" type="button" disabled={pageIndex === 0} onclick={() => (pageIndex -= 1)}>
          {i18n.t('common.prev')}
        </button>
        <button class="rl-btn small next" type="button" disabled={pageIndex >= pages - 1} onclick={() => (pageIndex += 1)}>
          {i18n.t('common.next')}
        </button>
      </span>
    </div>
  {/if}

  {#if list.data?.limitations}
    <p class="limitations rl-help">
      <strong>{i18n.t('pipelines.limitations')}</strong>
      <PlainText value={list.data.limitations} multiline />
    </p>
  {/if}
{/if}

<style>
  .muted {
    color: var(--text-secondary);
  }
  .name {
    font-weight: 600;
  }
  .class {
    color: var(--text-muted);
    font-size: var(--text-xs);
    overflow-wrap: anywhere;
  }
  .pager {
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: var(--space-3);
    margin-top: var(--space-3);
    color: var(--text-secondary);
  }
  .limitations {
    margin-top: var(--space-5);
  }
  .limitations strong {
    display: block;
  }
</style>
