<script lang="ts">
  import type { CatalogDatabase, CatalogProperty } from '../api/admin-model';
  import { useApp } from '../app/context';
  import type { JdbcFields } from './resource-forms';
  import PairsField from './PairsField.svelte';
  import TextField from './TextField.svelte';

  // The fields of a `jdbc-pool` resource (08-api.md: `jdbc-pool`): the kind of database, among
  // those the Engine tells ([databases], ADR-021), where it is, the account (its password is the
  // alias chosen below the type's fields, never typed), extra connection properties (those the
  // kind allows are said, with their rules, as the Engine tells them), the connections each run may
  // use and the timeouts. It says what the pool comes to: the capacity times the connections per run.
  interface Props {
    fields: JdbcFields;
    databases: CatalogDatabase[];
    errors: Record<string, string>;
    /** The capacity as typed, for the size of the pool. */
    capacity: string;
    /** A field changed: the errors said at these ids are no longer so. */
    onchange: (...ids: string[]) => void;
  }
  let { fields = $bindable(), databases, errors, capacity, onchange }: Props = $props();

  const { i18n } = useApp();
  const whole = (text: string, empty: number | null) =>
    text.trim() === '' ? empty : /^\d+$/.test(text.trim()) ? Number(text.trim()) : null;
  /** A property the kind allows, with its rule in words. */
  const described = (property: CatalogProperty) =>
    property.rule === 'text' && property.maxLength !== undefined
      ? i18n.t('resources.form.jdbc.property.text', { name: property.name, maxLength: property.maxLength })
      : property.rule === 'oneOf' && property.values !== undefined
        ? i18n.t('resources.form.jdbc.property.oneOf', { name: property.name, values: property.values.join(', ') })
        : property.name;
  const allowed = $derived.by(() => {
    const properties = databases.find((database) => database.kind === fields.kind)?.properties;
    if (properties === undefined) return undefined;
    return properties.length === 0
      ? i18n.t('resources.form.jdbc.properties.none')
      : i18n.t('resources.form.jdbc.properties.allowed', { properties: properties.map(described).join('; ') });
  });
  const pool = $derived.by(() => {
    const held = whole(capacity, null);
    const perRun = whole(fields.connectionsPerRun, 1);
    return {
      capacity: held ?? '?',
      perRun: perRun ?? '?',
      size: held !== null && perRun !== null ? String(held * perRun) : '?',
    };
  });
</script>

<div class="rl-field">
  <label for="jdbc-kind">{i18n.t('resources.setting.kind')}</label>
  <select
    id="jdbc-kind"
    class="rl-input"
    aria-invalid={errors['jdbc-kind'] ? 'true' : undefined}
    bind:value={fields.kind}
    onchange={() => onchange('jdbc-kind')}
  >
    {#each databases as database (database.kind)}
      <option value={database.kind}>{database.kind}</option>
    {/each}
  </select>
  {#if errors['jdbc-kind']}<span class="rl-field-error">{errors['jdbc-kind']}</span>{/if}
</div>

<div class="row">
  <TextField
    id="jdbc-host"
    label={i18n.t('resources.setting.host')}
    error={errors['jdbc-host']}
    bind:value={fields.host}
    oninput={() => onchange('jdbc-host', 'jdbc-settings')}
  />
  <TextField
    id="jdbc-port"
    label={i18n.t('resources.setting.port')}
    placeholder="5432"
    numeric
    error={errors['jdbc-port']}
    bind:value={fields.port}
    oninput={() => onchange('jdbc-port', 'jdbc-settings')}
  />
</div>
<TextField
  id="jdbc-database"
  label={i18n.t('resources.setting.database')}
  error={errors['jdbc-database']}
  bind:value={fields.database}
  oninput={() => onchange('jdbc-database', 'jdbc-settings')}
/>
<TextField
  id="jdbc-username"
  label={i18n.t('resources.setting.username')}
  help={i18n.t('resources.form.jdbc.username.help')}
  error={errors['jdbc-username']}
  bind:value={fields.username}
  oninput={() => onchange('jdbc-username', 'jdbc-settings')}
/>
<div class="rl-field" id="jdbc-settings">
  {#if errors['jdbc-settings']}<span class="rl-field-error">{errors['jdbc-settings']}</span>{/if}
</div>

<PairsField
  id="jdbc-properties"
  legend={i18n.t('resources.form.jdbc.properties')}
  help={i18n.t('resources.form.jdbc.properties.help')}
  {allowed}
  add={i18n.t('resources.form.jdbc.properties.add')}
  error={errors['jdbc-properties']}
  bind:pairs={fields.properties}
  onchange={() => onchange('jdbc-properties')}
/>

<TextField
  id="jdbc-per-run"
  label={i18n.t('resources.form.jdbc.perRun')}
  placeholder="1"
  numeric
  error={errors['jdbc-per-run']}
  bind:value={fields.connectionsPerRun}
  oninput={() => onchange('jdbc-per-run')}
/>
<p class="rl-notice info pool-size">
  {i18n.t('resources.form.jdbc.pool', pool)}
</p>

<fieldset id="jdbc-timeouts">
  <legend>{i18n.t('resources.form.timeouts')}</legend>
  <div class="row three">
    <TextField
      id="jdbc-connect-ms"
      label={i18n.t('resources.form.timeout.connectMs')}
      placeholder="10000"
      numeric
      error={errors['jdbc-connect-ms']}
      bind:value={fields.connectMs}
      oninput={() => onchange('jdbc-connect-ms', 'jdbc-timeouts')}
    />
    <TextField
      id="jdbc-statement-ms"
      label={i18n.t('resources.form.timeout.statementMs')}
      placeholder="300000"
      numeric
      error={errors['jdbc-statement-ms']}
      bind:value={fields.statementMs}
      oninput={() => onchange('jdbc-statement-ms', 'jdbc-timeouts')}
    />
    <TextField
      id="jdbc-quota-wait-ms"
      label={i18n.t('resources.form.timeout.quotaWaitMs')}
      placeholder="60000"
      numeric
      error={errors['jdbc-quota-wait-ms']}
      bind:value={fields.quotaWaitMs}
      oninput={() => onchange('jdbc-quota-wait-ms', 'jdbc-timeouts')}
    />
  </div>
  <span class="rl-help">{i18n.t('resources.form.timeouts.help')}</span>
  {#if errors['jdbc-timeouts']}<span class="rl-field-error">{errors['jdbc-timeouts']}</span>{/if}
</fieldset>

<style>
  .row {
    display: grid;
    grid-template-columns: 2fr 1fr;
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
  legend,
  :global(.pairs legend) {
    margin-bottom: var(--space-2);
    padding: 0;
    color: var(--text-secondary);
    font-size: var(--text-xs);
    font-weight: 600;
  }
  .pool-size {
    margin: 0;
  }
</style>
