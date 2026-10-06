<script lang="ts">
  import { useApp } from '../app/context';
  import { formatDateTime, formatRelative, utcOriginal } from '../i18n/format';

  // A time of the API (ISO-8601, UTC) as local time in the language of the screen. The tooltip has
  // the UTC value as the API sent it. With `relativeTo` it reads "3 minutes ago"; the tooltip then
  // has the local time as well.
  interface Props {
    iso: string;
    relativeTo?: Date;
  }
  let { iso, relativeTo }: Props = $props();

  const { i18n } = useApp();
  const local = $derived(formatDateTime(iso, i18n.locale));
  const text = $derived(relativeTo ? formatRelative(iso, relativeTo, i18n.locale) : local);
  const tooltip = $derived(relativeTo ? `${local} (${utcOriginal(iso)})` : utcOriginal(iso));
</script>

<time datetime={iso} title={tooltip}>{text}</time>
