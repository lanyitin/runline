<script lang="ts">
  import { useApp } from '../app/context';
  import type { FileFields } from './resource-forms';
  import TextField from './TextField.svelte';

  // The fields of a `file` resource: its path under the Engine's resource root (08-api.md).
  interface Props {
    fields: FileFields;
    errors: Record<string, string>;
    onchange: (...ids: string[]) => void;
  }
  let { fields = $bindable(), errors, onchange }: Props = $props();

  const { i18n } = useApp();
</script>

<TextField
  id="resource-path"
  label={i18n.t('resources.setting.path')}
  help={i18n.t('resources.form.path.help')}
  error={errors['resource-path']}
  bind:value={fields.path}
  oninput={() => onchange('resource-path')}
/>
