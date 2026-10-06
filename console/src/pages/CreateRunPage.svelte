<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import { createPolled } from '../api/polled.svelte';
  import type { Definition } from '../api/model';
  import { useApp } from '../app/context';
  import { runHref } from '../app/links';
  import { shortHash } from '../engine/info';
  import { describeApiError } from '../i18n/api-error';
  import { browserClock, pageVisibility } from '../runs/run-watch.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Link from '../ui/Link.svelte';
  import Notice from '../ui/Notice.svelte';
  import PlainText from '../ui/PlainText.svelte';

  // Makes a run (`POST /api/v1/runs`) of a pipeline the caller may use: the form is made from the
  // parameters the pipeline declares. The pipeline, and the parameters to start with, come from the
  // address (the pages of a pipeline and of a run lead here). What the Engine refuses is said with
  // its code in words, and a problem with a parameter is shown at that parameter's field.
  const { i18n, api, router } = useApp();

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

  const query = $derived(new URLSearchParams(router.search));
  const wantedHash = $derived(query.get('contentHash'));
  const wantedName = $derived(query.get('pipeline'));
  const definitions = $derived<Definition[]>(
    [...(list.data?.definitions ?? [])].sort(
      (a, b) => b.uploadedAt.localeCompare(a.uploadedAt) || a.name.localeCompare(b.name),
    ),
  );
  const keyOf = (d: Pick<Definition, 'contentHash' | 'name'>) => `${d.contentHash}/${d.name}`;
  const selected = $derived(
    definitions.find((d) => d.contentHash === wantedHash && d.name === wantedName) ?? null,
  );
  const missingFromAddress = $derived(
    list.status === 'ready' && wantedHash !== null && wantedName !== null && selected === null,
  );

  let values = $state<Record<string, string>>({});
  let fieldErrors = $state<Record<string, string>>({});
  let failure = $state.raw<ApiFailure | null>(null);
  let busy = $state(false);

  // The fields start empty, or with what the address gives for the parameters the pipeline has (the
  // way to run a run again).
  $effect(() => {
    const chosen = selected;
    const start: Record<string, string> = {};
    for (const parameter of chosen?.metadata.parameters ?? []) {
      start[parameter.name] = new URLSearchParams(router.search).get(`param.${parameter.name}`) ?? '';
    }
    values = start;
    fieldErrors = {};
    failure = null;
  });

  function choose(key: string) {
    const [contentHash, ...rest] = key.split('/');
    const name = rest.join('/');
    router.replace(
      key === ''
        ? '/runs/new'
        : `/runs/new?${new URLSearchParams({ contentHash, pipeline: name })}`,
    );
  }

  const label = (parameter: { required: boolean }) =>
    parameter.required ? i18n.t('common.required') : i18n.t('common.optional');

  async function create(event: SubmitEvent) {
    event.preventDefault();
    if (!selected || busy) return;
    failure = null;
    const errors: Record<string, string> = {};
    for (const parameter of selected.metadata.parameters) {
      if (parameter.required && parameter.default === null && (values[parameter.name] ?? '') === '') {
        errors[parameter.name] = i18n.t('createRun.requiredError');
      }
    }
    fieldErrors = errors;
    if (Object.keys(errors).length > 0) return;

    const parameters = Object.fromEntries(
      Object.entries(values).filter(([, value]) => value !== ''),
    );
    busy = true;
    try {
      const run = await api.createRun({
        contentHash: selected.contentHash,
        pipeline: selected.name,
        parameters,
      });
      router.navigate(runHref(run.runId));
    } catch (error) {
      failure = error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));
    } finally {
      busy = false;
    }
  }

  /** The problems of an `invalid_parameters` answer that are fields of this form, and the rest. */
  const split = $derived.by(() => {
    if (!failure || failure.status !== 422 || !selected) return { shown: failure, onFields: {} as Record<string, string> };
    const body = failure.body as { error?: string; problems?: Array<{ name?: unknown; problem?: unknown }> } | null;
    if (body?.error !== 'invalid_parameters' || !Array.isArray(body.problems)) {
      return { shown: failure, onFields: {} as Record<string, string> };
    }
    const declared = new Set(selected.metadata.parameters.map((p) => p.name));
    const onFields: Record<string, string> = {};
    const rest = [];
    for (const problem of body.problems) {
      if (typeof problem?.name === 'string' && typeof problem.problem === 'string' && declared.has(problem.name)) {
        onFields[problem.name] =
          problem.problem === 'missing'
            ? i18n.t('createRun.requiredError')
            : describeApiError(i18n.translate, 422, { problems: [problem] }).problems[0];
      } else {
        rest.push(problem);
      }
    }
    return { shown: new ApiFailure(failure.status, { ...body, problems: rest }), onFields };
  });
  const shownErrors = $derived({ ...split.onFields, ...fieldErrors });
  const warningText = (kind: string, resource: string, message: string) =>
    i18n.translate.has(`warning.${kind}`) ? i18n.translate(`warning.${kind}`, { resource }) : message;
</script>

<div class="rl-page-head">
  <div>
    <h1>{i18n.t('page.runNew.title')}</h1>
    <p class="sub">{i18n.t('page.runNew.intro')}</p>
  </div>
</div>

{#if list.status === 'failed' && list.error}
  <div class="rl-stack">
    <ApiErrorNotice failure={list.error} />
    <div><button class="rl-btn retry" type="button" onclick={() => list.reload()}>{i18n.t('common.retry')}</button></div>
  </div>
{:else if list.status === 'loading'}
  <p class="muted" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
{:else if definitions.length === 0}
  <div class="rl-empty">
    <strong>{i18n.t('createRun.noDefinitions')}</strong>
    <Link href="/upload" class="rl-btn primary">{i18n.t('page.upload.title')}</Link>
  </div>
{:else}
  <form class="rl-card form" onsubmit={create} novalidate>
    <div class="rl-stack">
      {#if missingFromAddress}
        <Notice tone="warning">{i18n.t('createRun.notFound')}</Notice>
      {/if}

      <div class="rl-field">
        <label for="create-run-pipeline">{i18n.t('createRun.pipeline')}</label>
        <select
          id="create-run-pipeline"
          class="rl-input"
          value={selected ? keyOf(selected) : ''}
          onchange={(event) => choose(event.currentTarget.value)}
        >
          <option value="">{i18n.t('createRun.choose')}</option>
          {#each definitions as definition (keyOf(definition))}
            <option value={keyOf(definition)}>
              {definition.name} · {shortHash(definition.contentHash)} · {definition.uploadedBy}
            </option>
          {/each}
        </select>
      </div>

      {#if selected}
        {#if selected.verdict === 'UNSAFE'}
          <div class="unsafe">
            <Notice tone={selected.allowUnsafeExecution ? 'warning' : 'danger'}>
              {selected.allowUnsafeExecution ? i18n.t('createRun.unsafe.allowed') : i18n.t('createRun.unsafe.notAllowed')}
            </Notice>
          </div>
        {/if}
        {#each selected.warnings as warning, index (index)}
          <div class="warning-line">
            <Notice tone="warning"><PlainText value={warningText(warning.kind, warning.resource, warning.message)} /></Notice>
          </div>
        {/each}

        <fieldset class="params">
          <legend>{i18n.t('createRun.parameters')}</legend>
          {#if selected.metadata.parameters.length === 0}
            <p>{i18n.t('createRun.noParams')}</p>
          {/if}
          {#each selected.metadata.parameters as parameter (parameter.name)}
            <div class="rl-field">
              <label for={`param-${parameter.name}`}>
                <PlainText value={parameter.name} mono />
                <span class="kind" class:required={parameter.required}>{label(parameter)}</span>
              </label>
              <input
                id={`param-${parameter.name}`}
                class="rl-input mono"
                type="text"
                name={`param.${parameter.name}`}
                autocomplete="off"
                spellcheck="false"
                placeholder={parameter.default ?? ''}
                aria-invalid={shownErrors[parameter.name] ? 'true' : undefined}
                aria-describedby={`param-help-${parameter.name}`}
                bind:value={values[parameter.name]}
                oninput={() => {
                  delete fieldErrors[parameter.name];
                  if (split.onFields[parameter.name]) failure = null;
                }}
              />
              <span class="rl-help" id={`param-help-${parameter.name}`}>
                {#if parameter.default !== null}
                  {i18n.t('createRun.default', { value: parameter.default })}
                  {i18n.t('createRun.leaveEmpty')}
                {/if}
              </span>
              {#if shownErrors[parameter.name]}
                <span class="rl-field-error">{shownErrors[parameter.name]}</span>
              {/if}
            </div>
          {/each}
        </fieldset>
      {/if}

      {#if split.shown}
        <ApiErrorNotice failure={split.shown} />
      {/if}

      <div class="rl-actions">
        <button class="rl-btn primary submit" type="submit" disabled={!selected || busy}>
          {busy ? i18n.t('createRun.busy') : i18n.t('createRun.submit')}
        </button>
      </div>
    </div>
  </form>
{/if}

<style>
  .muted {
    color: var(--text-secondary);
  }
  .form {
    max-width: 44rem;
  }
  fieldset {
    display: grid;
    gap: var(--space-4);
    margin: 0;
    padding: 0;
    border: 0;
  }
  legend {
    margin-bottom: var(--space-2);
    padding: 0;
    color: var(--text-muted);
    font-size: var(--text-2xs);
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }
  .kind {
    margin-left: var(--space-2);
    color: var(--text-muted);
    font-weight: 400;
  }
  .kind.required {
    color: var(--danger-text);
    font-weight: 600;
  }
</style>
