<script lang="ts">
  import { useApp } from '../app/context';
  import type { TraceExportFields } from './resource-forms';
  import TextField from './TextField.svelte';

  // The fields of a `trace-export` resource (08-api.md: `trace-export`; ADR-46): the full address of
  // the OTLP/HTTP traces signal, which the Engine adds no path to, and the `service.name` of the
  // exported spans. The credentials are the alias chosen below these fields and the certificates
  // those of an https address (the dialog); nothing here takes a secret.
  interface Props {
    fields: TraceExportFields;
    errors: Record<string, string>;
    /** A field changed: the errors said at these ids are no longer so. */
    onchange: (...ids: string[]) => void;
  }
  let { fields = $bindable(), errors, onchange }: Props = $props();

  const { i18n } = useApp();
</script>

<TextField
  id="trace-endpoint"
  label={i18n.t('resources.setting.endpoint')}
  help={i18n.t('resources.form.trace.endpoint.help')}
  error={errors['trace-endpoint']}
  bind:value={fields.endpoint}
  oninput={() => onchange('trace-endpoint')}
/>
<TextField
  id="trace-service-name"
  label={i18n.t('resources.setting.serviceName')}
  help={i18n.t('resources.form.trace.serviceName.help')}
  error={errors['trace-service-name']}
  bind:value={fields.serviceName}
  oninput={() => onchange('trace-service-name')}
/>
{#if errors['trace-settings']}<span class="rl-field-error">{errors['trace-settings']}</span>{/if}
