<script lang="ts">
  import { ApiFailure } from '../api/failure';
  import type { Definition } from '../api/model';
  import type { Trigger } from '../api/admin-model';
  import { createPolled } from '../api/polled.svelte';
  import { describeCron, parseCron } from '../admin/cron';
  import SecretDialog from '../admin/SecretDialog.svelte';
  import { useApp } from '../app/context';
  import { triggerHref } from '../app/links';
  import { shortHash } from '../engine/info';
  import { describeApiError } from '../i18n/api-error';
  import { enumLabel } from '../i18n/enums';
  import { browserClock, pageVisibility } from '../runs/run-watch.svelte';
  import ApiErrorNotice from '../ui/ApiErrorNotice.svelte';
  import Link from '../ui/Link.svelte';
  import PlainText from '../ui/PlainText.svelte';

  // Makes a trigger (`POST /api/v1/triggers`) or changes one (`PATCH`): a cron trigger with its
  // expression and time zone, or a webhook. What it runs is one version of one pipeline, and its
  // parameters are the ones that pipeline declares. A cron expression is said in words as it is
  // typed; the Engine decides what it accepts, so what the page cannot read has no preview and is
  // not called wrong. What the Engine finds wrong is said at the field it is about. The secret of a
  // webhook is shown once, in a dialog that has to be answered (SecretDialog), and nowhere else.
  interface Props {
    mode: 'create' | 'edit';
  }
  let { mode }: Props = $props();

  const { i18n, api, router } = useApp();
  // svelte-ignore state_referenced_locally
  const editing = mode === 'edit';
  // The address at the time the page opened: the form is not made again when a field changes it.
  const start = new URLSearchParams(router.search);
  const triggerName = start.get('name') ?? '';

  // svelte-ignore state_referenced_locally
  const loaded = createPolled({
    load: async () => ({
      definitions: (await api.definitions()).definitions,
      trigger: editing ? await api.trigger(triggerName) : null,
    }),
    clock: browserClock,
    visibility: pageVisibility,
    intervalMs: 0,
    auto: false,
  });
  $effect(() => {
    void loaded.start();
    return () => loaded.dispose();
  });

  const definitions = $derived<Definition[]>(
    [...(loaded.data?.definitions ?? [])].sort(
      (a, b) => b.uploadedAt.localeCompare(a.uploadedAt) || a.name.localeCompare(b.name),
    ),
  );
  const keyOf = (d: { contentHash: string; name: string }) => `${d.contentHash}/${d.name}`;

  let name = $state('');
  let kind = $state<'cron' | 'webhook'>('cron');
  let targetKey = $state('');
  let cron = $state('');
  let zone = $state('UTC');
  let enabled = $state(true);
  let values = $state<Record<string, string>>({});
  let fieldErrors = $state<Record<string, string>>({});
  let failure = $state.raw<ApiFailure | null>(null);
  let busy = $state(false);
  let created = $state<{ name: string; webhookPath: string; secret: string } | null>(null);

  const selected = $derived(definitions.find((d) => keyOf(d) === targetKey) ?? null);
  const original = $derived<Trigger | null>(loaded.data?.trigger ?? null);

  /** Chooses what the trigger runs, with the values of the parameters it starts from. */
  function choose(key: string, given: Record<string, string> = {}) {
    targetKey = key;
    const definition = definitions.find((d) => keyOf(d) === key);
    values = Object.fromEntries(
      (definition?.metadata.parameters ?? []).map((p) => [p.name, given[p.name] ?? '']),
    );
    fieldErrors = {};
    failure = null;
  }

  // The form starts when what it needs has been read: from the address (making one) or from the
  // trigger (changing one).
  let started = false;
  $effect(() => {
    if (started || loaded.data === null) return;
    started = true;
    const trigger = loaded.data.trigger;
    if (trigger) {
      kind = trigger.kind === 'webhook' ? 'webhook' : 'cron';
      cron = trigger.cron ?? '';
      zone = trigger.timeZone ?? 'UTC';
      enabled = trigger.enabled;
      choose(`${trigger.contentHash}/${trigger.pipeline}`, trigger.parameters);
      return;
    }
    const contentHash = start.get('contentHash');
    const pipeline = start.get('pipeline');
    if (contentHash && pipeline) choose(`${contentHash}/${pipeline}`);
  });

  const PRESETS = ['* * * * *', '*/5 * * * *', '0 * * * *', '0 3 * * *', '0 9 * * 1-5'];
  const zones = $derived.by(() => {
    const known =
      typeof Intl.supportedValuesOf === 'function' ? Intl.supportedValuesOf('timeZone') : [];
    return known.includes('UTC') ? known : ['UTC', ...known];
  });
  const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  const preview = $derived.by(() => {
    if (cron.trim() === '') return '';
    return (
      describeCron(cron, i18n.translate, i18n.locale) ??
      (parseCron(cron) === null && cron.trim().split(/\s+/).length !== 5
        ? i18n.t('triggerForm.cron.noPreview.fields')
        : i18n.t('triggerForm.cron.noPreview'))
    );
  });

  const asFailure = (error: unknown) =>
    error instanceof ApiFailure ? error : new ApiFailure(0, null, String(error));

  /** The failure of the Engine as errors at fields, and what is left of it for the page. */
  function place(refused: ApiFailure): { errors: Record<string, string>; rest: ApiFailure | null } {
    const body = (refused.body ?? {}) as { error?: string; problem?: string; problems?: unknown };
    const described = describeApiError(i18n.translate, refused.status, refused.body);
    const errors: Record<string, string> = {};
    if (body.error === 'trigger_exists') {
      errors['trigger-name'] = described.message;
    } else if (body.error === 'invalid_trigger') {
      const at: Record<string, string> = {
        name: 'trigger-name',
        cron_required: 'trigger-cron',
        cron_expression: 'trigger-cron',
        time_zone: 'trigger-zone',
      };
      const id = at[body.problem ?? ''];
      if (id) errors[id] = described.problems[0] ?? described.message;
    } else if (body.error === 'invalid_parameters' && Array.isArray(body.problems)) {
      const declared = new Set(selected?.metadata.parameters.map((p) => p.name) ?? []);
      const rest: unknown[] = [];
      for (const item of body.problems as Array<{ name?: unknown; problem?: unknown }>) {
        if (typeof item?.name === 'string' && declared.has(item.name) && item.problem === 'missing') {
          errors[`param-${item.name}`] = i18n.t('createRun.requiredError');
        } else {
          rest.push(item);
        }
      }
      return {
        errors,
        rest:
          rest.length === 0
            ? null
            : new ApiFailure(refused.status, { ...(refused.body as object), problems: rest }),
      };
    }
    return { errors, rest: Object.keys(errors).length > 0 ? null : refused };
  }

  async function save(event: SubmitEvent) {
    event.preventDefault();
    if (busy) return;
    failure = null;
    const errors: Record<string, string> = {};
    if (!editing && name.trim() === '') errors['trigger-name'] = i18n.t('triggerForm.name.required');
    if (selected === null) errors['trigger-target'] = i18n.t('triggerForm.target.required');
    if (kind === 'cron' && cron.trim() === '') errors['trigger-cron'] = i18n.t('triggerForm.cron.required');
    for (const parameter of selected?.metadata.parameters ?? []) {
      if (parameter.required && parameter.default === null && (values[parameter.name] ?? '') === '') {
        errors[`param-${parameter.name}`] = i18n.t('createRun.requiredError');
      }
    }
    fieldErrors = errors;
    if (Object.keys(errors).length > 0 || selected === null) return;

    const parameters = Object.fromEntries(Object.entries(values).filter(([, value]) => value !== ''));
    const schedule = kind === 'cron' ? { cron: cron.trim(), timeZone: zone.trim() || undefined } : {};
    busy = true;
    try {
      if (editing) {
        await api.updateTrigger(triggerName, {
          contentHash: selected.contentHash,
          pipeline: selected.name,
          parameters,
          enabled,
          ...schedule,
        });
        router.navigate(triggerHref(triggerName));
      } else {
        const made = await api.createTrigger({
          name: name.trim(),
          kind,
          contentHash: selected.contentHash,
          pipeline: selected.name,
          parameters,
          enabled,
          ...schedule,
        });
        if (made.secret !== null && made.trigger.webhookPath !== null) {
          // The one moment the secret exists: the dialog holds it, and goes on to the trigger.
          created = { name: made.trigger.name, webhookPath: made.trigger.webhookPath, secret: made.secret };
        } else {
          router.navigate(triggerHref(made.trigger.name));
        }
      }
    } catch (error) {
      const placed = place(asFailure(error));
      fieldErrors = placed.errors;
      failure = placed.rest;
    } finally {
      busy = false;
    }
  }

  function secretKept() {
    const done = created;
    created = null;
    if (done) router.navigate(triggerHref(done.name));
  }

  const notFound = $derived(loaded.status === 'failed' && loaded.error?.status === 404);
  const errorOf = (id: string) => fieldErrors[id] ?? null;
</script>

<div class="rl-page-head">
  <div>
    <h1>{i18n.t(editing ? 'page.triggerEdit.title' : 'page.triggerNew.title')}</h1>
    <p class="sub">{i18n.t(editing ? 'page.triggerEdit.intro' : 'page.triggerNew.intro')}</p>
  </div>
</div>

{#if loaded.status === 'failed' && loaded.error}
  <div class="rl-stack">
    <ApiErrorNotice failure={loaded.error} />
    {#if !notFound}
      <div><button class="rl-btn retry" type="button" onclick={() => loaded.reload()}>{i18n.t('common.retry')}</button></div>
    {/if}
    <p><Link href="/triggers">{i18n.t('trigger.back')}</Link></p>
  </div>
{:else if loaded.status === 'loading'}
  <p class="muted" role="status" aria-busy="true">{i18n.t('common.loading')}</p>
{:else}
  <form class="rl-card form" onsubmit={save} novalidate>
    <div class="rl-stack">
      {#if editing}
        <dl class="rl-dl">
          <dt>{i18n.t('triggers.col.name')}</dt>
          <dd class="fixed-name"><PlainText value={triggerName} mono /></dd>
          <dt>{i18n.t('triggers.col.kind')}</dt>
          <dd class="fixed-kind">{enumLabel(i18n.translate, 'triggerKind', original?.kind ?? kind)}</dd>
        </dl>
      {:else}
        <div class="rl-field">
          <label for="trigger-name">{i18n.t('triggers.col.name')}</label>
          <input
            id="trigger-name"
            class="rl-input mono"
            type="text"
            autocomplete="off"
            spellcheck="false"
            aria-invalid={errorOf('trigger-name') ? 'true' : undefined}
            bind:value={name}
            oninput={() => delete fieldErrors['trigger-name']}
          />
          <span class="rl-help">{i18n.t('triggerForm.name.help')}</span>
          {#if errorOf('trigger-name')}<span class="rl-field-error">{errorOf('trigger-name')}</span>{/if}
        </div>

        <fieldset class="kinds">
          <legend>{i18n.t('triggers.col.kind')}</legend>
          <label><input type="radio" name="kind" value="cron" bind:group={kind} /> {i18n.t('triggerForm.kind.cron')}</label>
          <label><input type="radio" name="kind" value="webhook" bind:group={kind} /> {i18n.t('triggerForm.kind.webhook')}</label>
        </fieldset>
      {/if}

      <div class="rl-field">
        <label for="trigger-target">{i18n.t('triggerForm.target')}</label>
        <select
          id="trigger-target"
          class="rl-input"
          value={targetKey}
          aria-invalid={errorOf('trigger-target') ? 'true' : undefined}
          onchange={(event) => choose(event.currentTarget.value)}
        >
          <option value="">{i18n.t('createRun.choose')}</option>
          {#each definitions as definition (keyOf(definition))}
            <option value={keyOf(definition)}>
              {definition.name} · {shortHash(definition.contentHash)} · {definition.uploadedBy}
            </option>
          {/each}
        </select>
        <span class="rl-help">{i18n.t('triggerForm.target.help')}</span>
        {#if errorOf('trigger-target')}<span class="rl-field-error">{errorOf('trigger-target')}</span>{/if}
      </div>

      {#if kind === 'cron'}
        <div class="rl-field">
          <label for="trigger-cron">{i18n.t('triggerForm.cron')}</label>
          <input
            id="trigger-cron"
            class="rl-input mono"
            type="text"
            autocomplete="off"
            spellcheck="false"
            placeholder="30 9 * * 1-5"
            aria-invalid={errorOf('trigger-cron') ? 'true' : undefined}
            aria-describedby="trigger-cron-help"
            bind:value={cron}
            oninput={() => delete fieldErrors['trigger-cron']}
          />
          <span class="rl-help" id="trigger-cron-help">{i18n.t('triggerForm.cron.help')}</span>
          <p class="preview" aria-live="polite">{preview}</p>
          <div class="presets">
            {#each PRESETS as expression (expression)}
              <button class="rl-btn small preset" type="button" onclick={() => (cron = expression)}>
                {describeCron(expression, i18n.translate, i18n.locale)}
              </button>
            {/each}
          </div>
          {#if errorOf('trigger-cron')}<span class="rl-field-error">{errorOf('trigger-cron')}</span>{/if}
        </div>

        <div class="rl-field">
          <label for="trigger-zone">{i18n.t('triggerForm.zone')}</label>
          <input
            id="trigger-zone"
            class="rl-input mono"
            type="text"
            list="trigger-zones"
            autocomplete="off"
            spellcheck="false"
            aria-invalid={errorOf('trigger-zone') ? 'true' : undefined}
            bind:value={zone}
            oninput={() => delete fieldErrors['trigger-zone']}
          />
          <datalist id="trigger-zones">
            {#each zones as option (option)}<option value={option}></option>{/each}
          </datalist>
          <span class="rl-help">
            {i18n.t('triggerForm.zone.help')}
            {#if browserZone && browserZone !== zone}
              <button class="link-button" type="button" onclick={() => (zone = browserZone)}>
                {i18n.t('triggerForm.zone.browser', { zone: browserZone })}
              </button>
            {/if}
          </span>
          {#if errorOf('trigger-zone')}<span class="rl-field-error">{errorOf('trigger-zone')}</span>{/if}
        </div>
      {:else}
        <div class="rl-notice info">{i18n.t('triggerForm.webhook.help')}</div>
      {/if}

      {#if selected}
        <fieldset class="params">
          <legend>{i18n.t('createRun.parameters')}</legend>
          {#if selected.metadata.parameters.length === 0}
            <p>{i18n.t('createRun.noParams')}</p>
          {:else}
            <p class="rl-help">{i18n.t('triggerForm.params.help')}</p>
          {/if}
          {#each selected.metadata.parameters as parameter (parameter.name)}
            <div class="rl-field">
              <label for={`param-${parameter.name}`}>
                <PlainText value={parameter.name} mono />
                <span class="kind" class:required={parameter.required}>
                  {parameter.required ? i18n.t('common.required') : i18n.t('common.optional')}
                </span>
              </label>
              <input
                id={`param-${parameter.name}`}
                class="rl-input mono"
                type="text"
                autocomplete="off"
                spellcheck="false"
                placeholder={parameter.default ?? ''}
                aria-invalid={errorOf(`param-${parameter.name}`) ? 'true' : undefined}
                bind:value={values[parameter.name]}
                oninput={() => delete fieldErrors[`param-${parameter.name}`]}
              />
              {#if parameter.default !== null}
                <span class="rl-help">{i18n.t('createRun.default', { value: parameter.default })}</span>
              {/if}
              {#if errorOf(`param-${parameter.name}`)}
                <span class="rl-field-error">{errorOf(`param-${parameter.name}`)}</span>
              {/if}
            </div>
          {/each}
        </fieldset>
      {/if}

      <label class="enabled">
        <input id="trigger-enabled" type="checkbox" bind:checked={enabled} />
        {i18n.t('triggerForm.enabled')}
      </label>

      {#if failure}
        <ApiErrorNotice {failure} />
      {/if}

      <div class="rl-actions">
        <button class="rl-btn primary submit" type="submit" disabled={busy}>
          {busy ? i18n.t('triggerForm.busy') : i18n.t(editing ? 'triggerForm.save' : 'triggerForm.create')}
        </button>
        <Link href={editing ? triggerHref(triggerName) : '/triggers'} class="rl-btn">{i18n.t('common.cancel')}</Link>
      </div>
    </div>
  </form>
{/if}

{#if created}
  <SecretDialog name={created.name} webhookPath={created.webhookPath} secret={created.secret} onclose={secretKept} />
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
  .kinds {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-5);
  }
  .kinds legend {
    flex-basis: 100%;
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
  .preview {
    min-height: 1.5em;
    margin: var(--space-1) 0 0;
    color: var(--accent-text);
    font-weight: 600;
  }
  .presets {
    display: flex;
    flex-wrap: wrap;
    gap: var(--space-2);
  }
  .link-button {
    padding: 0;
    border: 0;
    background: none;
    color: var(--accent-text);
    font: inherit;
    cursor: pointer;
    text-decoration: underline;
  }
  .enabled {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }
</style>
