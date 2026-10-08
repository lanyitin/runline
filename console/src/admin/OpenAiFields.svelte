<script lang="ts">
  import type { ResourceTypeCatalog } from '../api/admin-model';
  import { useApp } from '../app/context';
  import type { OpenAiFields } from './resource-forms';
  import PairsField from './PairsField.svelte';
  import TextField from './TextField.svelte';

  // The fields of an `openai-compatible` resource (08-api.md: `openai-compatible`; ADR-019 decision
  // 4): the base address, the organization and project, extra headers (none of them a credential:
  // the key is the alias chosen below these fields), the entries of the endpoint catalog that are
  // enabled, the request parameters with their default, lock and ceiling, the allowed models, the
  // five timeouts and the requests each run may have in flight. It says what a lock and a ceiling
  // do, and what the limit of requests at once comes to: the capacity times the requests per run.
  // The entries, by their group, and the parameters are those the Engine tells ([catalog]); each
  // entry says whether a new resource has it and whether it makes, cancels or deletes what the
  // service keeps, and a group can be enabled or disabled at once (the entries are still what is
  // stored).
  interface Props {
    fields: OpenAiFields;
    catalog: NonNullable<ResourceTypeCatalog['openAi']>;
    errors: Record<string, string>;
    /** The capacity as typed, for the limit of requests at once. */
    capacity: string;
    /** A field changed: the errors said at these ids are no longer so. */
    onchange: (...ids: string[]) => void;
  }
  let { fields = $bindable(), catalog, errors, capacity, onchange }: Props = $props();

  const { i18n } = useApp();
  const whole = (text: string, empty: number | null) =>
    text.trim() === '' ? empty : /^\d+$/.test(text.trim()) ? Number(text.trim()) : null;
  const limit = $derived.by(() => {
    const held = whole(capacity, null);
    const perRun = whole(fields.requestsPerRun, 1);
    return {
      capacity: held ?? '?',
      perRun: perRun ?? '?',
      limit: held !== null && perRun !== null ? String(held * perRun) : '?',
    };
  });
  /** What is wrong with the parameters: the Engine's word, then each parameter's, by its name. */
  const parameterErrors = $derived([
    ...(errors['openai-parameters'] ? [errors['openai-parameters']] : []),
    ...catalog.requestParameters.flatMap(({ name }) =>
      [errors[`openai-parameter-${name}`], errors[`openai-max-${name}`]]
        .filter((error) => error !== undefined)
        .map((error) => `${name}: ${error}`),
    ),
  ]);
  /** The entries by their group, in the order of the catalog. */
  const groups = $derived.by(() => {
    const byGroup = new Map<string, typeof catalog.endpoints>();
    for (const entry of catalog.endpoints) byGroup.set(entry.group, [...(byGroup.get(entry.group) ?? []), entry]);
    return [...byGroup].map(([name, entries]) => ({ name, entries }));
  });
  /** Enables or disables every entry of a group; the others stay as they are. */
  function setGroup(entries: typeof catalog.endpoints, enabled: boolean) {
    const ids = entries.map((entry) => entry.id);
    const others = fields.endpoints.filter((id) => !ids.includes(id));
    fields.endpoints = enabled ? [...others, ...ids] : others;
    onchange('openai-endpoints');
  }
  const TIMEOUTS = [
    ['connectMs', 'openai-connect-ms', '10000'],
    ['firstByteMs', 'openai-first-byte-ms', '900000'],
    ['idleMs', 'openai-idle-ms', '300000'],
    ['totalMs', 'openai-total-ms', ''],
    ['quotaWaitMs', 'openai-quota-wait-ms', '60000'],
  ] as const;
</script>

<TextField
  id="openai-base-url"
  label={i18n.t('resources.setting.baseUrl')}
  help={i18n.t('resources.form.openai.baseUrl.help')}
  error={errors['openai-base-url']}
  bind:value={fields.baseUrl}
  oninput={() => onchange('openai-base-url', 'openai-settings')}
/>
<div class="row">
  <TextField
    id="openai-organization"
    label={i18n.t('resources.setting.organization')}
    bind:value={fields.organization}
    oninput={() => onchange('openai-headers')}
  />
  <TextField
    id="openai-project"
    label={i18n.t('resources.setting.project')}
    bind:value={fields.project}
    oninput={() => onchange('openai-headers')}
  />
</div>
<PairsField
  id="openai-headers"
  legend={i18n.t('resources.form.openai.headers')}
  help={i18n.t('resources.form.openai.headers.help')}
  add={i18n.t('resources.form.openai.headers.add')}
  error={errors['openai-headers']}
  bind:pairs={fields.headers}
  onchange={() => onchange('openai-headers')}
/>
<div class="rl-field" id="openai-settings">
  {#if errors['openai-settings']}<span class="rl-field-error">{errors['openai-settings']}</span>{/if}
</div>

<fieldset id="openai-endpoints">
  <legend>{i18n.t('resources.form.openai.endpoints')}</legend>
  <span class="rl-help">{i18n.t('resources.form.openai.endpoints.help')}</span>
  {#each groups as group (group.name)}
    <div class="group" data-group={group.name}>
      <div class="group-head">
        <span class="group-name rl-mono">{group.name}</span>
        <button class="rl-btn small" type="button" onclick={() => setGroup(group.entries, true)}>
          {i18n.t('resources.form.openai.group.enable')}
        </button>
        <button class="rl-btn small" type="button" onclick={() => setGroup(group.entries, false)}>
          {i18n.t('resources.form.openai.group.disable')}
        </button>
      </div>
      <div class="endpoints">
        {#each group.entries as entry (entry.id)}
          <label class="endpoint">
            <input
              type="checkbox"
              name="endpoint"
              value={entry.id}
              bind:group={fields.endpoints}
              onchange={() => onchange('openai-endpoints')}
            />
            <span class="rl-mono">{entry.id}</span>
            <span class="rl-help rl-mono">{entry.method} {entry.path}</span>
            {#if entry.defaultEnabled}<span class="tag default">{i18n.t('resources.form.openai.endpoint.default')}</span>{/if}
            {#if entry.stateful}<span class="tag stateful">{i18n.t('resources.form.openai.endpoint.stateful')}</span>{/if}
          </label>
        {/each}
      </div>
    </div>
  {/each}
  {#if errors['openai-endpoints']}<span class="rl-field-error">{errors['openai-endpoints']}</span>{/if}
</fieldset>

<fieldset id="openai-parameters">
  <legend>{i18n.t('resources.form.openai.parameters')}</legend>
  <span class="rl-help">{i18n.t('resources.form.openai.parameters.help')}</span>
  <div class="rl-table-wrap">
    <table class="rl-table parameters">
      <thead>
        <tr>
          <th>{i18n.t('resources.form.openai.parameter')}</th>
          <th>{i18n.t('resources.form.openai.default')}</th>
          <th>{i18n.t('resources.form.openai.locked')}</th>
          <th>{i18n.t('resources.form.openai.max')}</th>
        </tr>
      </thead>
      <tbody>
        {#each catalog.requestParameters as parameter (parameter.name)}
          {@const typed = fields.parameters[parameter.name]}
          <tr>
            <td class="mono"><label for="openai-parameter-{parameter.name}">{parameter.name}</label></td>
            <td>
              <input
                id="openai-parameter-{parameter.name}"
                class="rl-input mono"
                type="text"
                autocomplete="off"
                spellcheck="false"
                inputmode={parameter.kind === 'number' ? 'decimal' : undefined}
                aria-invalid={errors[`openai-parameter-${parameter.name}`] ? 'true' : undefined}
                bind:value={typed.value}
                oninput={() => onchange(`openai-parameter-${parameter.name}`, 'openai-parameters')}
              />
            </td>
            <td>
              <input
                id="openai-locked-{parameter.name}"
                type="checkbox"
                aria-label={i18n.t('resources.form.openai.lockedOf', { name: parameter.name })}
                bind:checked={typed.locked}
                onchange={() => onchange('openai-parameters')}
              />
            </td>
            <td>
              {#if parameter.ceiling}
                <input
                  id="openai-max-{parameter.name}"
                  class="rl-input mono"
                  type="text"
                  autocomplete="off"
                  inputmode="decimal"
                  aria-label={i18n.t('resources.form.openai.maxOf', { name: parameter.name })}
                  aria-invalid={errors[`openai-max-${parameter.name}`] ? 'true' : undefined}
                  bind:value={typed.max}
                  oninput={() => onchange(`openai-max-${parameter.name}`, 'openai-parameters')}
                />
              {/if}
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  </div>
  <TextField
    id="openai-allowed-models"
    label={i18n.t('resources.form.openai.allowedModels')}
    help={i18n.t('resources.form.openai.allowedModels.help')}
    bind:value={fields.allowedModels}
    oninput={() => onchange('openai-parameters')}
  />
  {#if parameterErrors.length > 0}<span class="rl-field-error">{parameterErrors.join(' ')}</span>{/if}
</fieldset>

<fieldset id="openai-timeouts">
  <legend>{i18n.t('resources.form.timeouts')}</legend>
  <div class="row three">
    {#each TIMEOUTS as [key, id, fallback] (id)}
      <TextField
        {id}
        label={i18n.translate(`resources.form.timeout.${key}`)}
        placeholder={fallback === '' ? i18n.t('resources.form.timeout.none') : fallback}
        numeric
        error={errors[id]}
        bind:value={fields[key]}
        oninput={() => onchange(id, 'openai-timeouts')}
      />
    {/each}
  </div>
  <span class="rl-help">{i18n.t('resources.form.openai.timeouts.help')}</span>
  {#if errors['openai-timeouts']}<span class="rl-field-error">{errors['openai-timeouts']}</span>{/if}
</fieldset>

<TextField
  id="openai-per-run"
  label={i18n.t('resources.form.openai.perRun')}
  placeholder="1"
  numeric
  error={errors['openai-per-run']}
  bind:value={fields.requestsPerRun}
  oninput={() => onchange('openai-per-run')}
/>
<p class="rl-notice info request-limit">{i18n.t('resources.form.openai.limit', limit)}</p>

<style>
  .row {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: var(--space-3);
  }
  .row.three {
    grid-template-columns: repeat(3, 1fr);
  }
  fieldset {
    display: grid;
    gap: var(--space-2);
    margin: 0;
    padding: 0;
    border: 0;
  }
  legend {
    margin-bottom: var(--space-2);
    padding: 0;
    color: var(--text-secondary);
    font-size: var(--text-xs);
    font-weight: 600;
  }
  .group {
    display: grid;
    gap: var(--space-1);
  }
  .group-head {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--text-sm);
    font-weight: 600;
  }
  .endpoints {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(16rem, 1fr));
    gap: var(--space-1) var(--space-3);
  }
  .tag {
    padding: 0 var(--space-2);
    border-radius: var(--radius-pill);
    font-size: var(--text-2xs);
    font-weight: 700;
  }
  .tag.default {
    background: var(--accent-tint);
    color: var(--accent-text);
  }
  .tag.stateful {
    background: var(--warning-tint);
    color: var(--warning-text);
  }
  .endpoint {
    display: flex;
    flex-wrap: wrap;
    align-items: baseline;
    gap: var(--space-2);
    font-size: var(--text-sm);
  }
  .parameters td {
    padding: var(--space-1) var(--space-2);
    vertical-align: middle;
  }
  .parameters .rl-input {
    padding: var(--space-1) var(--space-2);
  }
  .request-limit {
    margin: 0;
  }
</style>
