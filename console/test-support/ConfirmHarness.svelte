<script lang="ts">
  import type { ApiFailure } from '../src/api/failure';
  import ConfirmDialog from '../src/ui/ConfirmDialog.svelte';

  // A confirmation that is open from the start, for the tests of the confirmation.
  interface Props {
    danger?: boolean;
    busy?: boolean;
    failure?: ApiFailure | null;
    onconfirm: () => void;
    oncancel: () => void;
  }
  let { danger = false, busy = false, failure = null, onconfirm, oncancel }: Props = $props();
</script>

<ConfirmDialog
  title="Delete the version?"
  confirmLabel="Delete it"
  {danger}
  {busy}
  {failure}
  {onconfirm}
  {oncancel}
>
  <p>It cannot be undone.</p>
</ConfirmDialog>
