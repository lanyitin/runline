<script lang="ts">
  // The dot of a status: only a mark beside words, never the only sign (it is hidden from screen
  // readers, and the words beside it carry the meaning).
  interface Props {
    tone: 'success' | 'danger' | 'warning' | 'running' | 'neutral';
    /** A soft glow, for the Engine's own health. */
    glow?: boolean;
    pulse?: boolean;
  }
  let { tone, glow = false, pulse = false }: Props = $props();
</script>

<span class="dot {tone}" class:glow class:pulse aria-hidden="true"></span>

<style>
  .dot {
    display: inline-block;
    width: 8px;
    height: 8px;
    flex: none;
    border-radius: 50%;
    background: var(--neutral);
  }
  .success {
    background: var(--success);
  }
  .danger {
    background: var(--danger);
  }
  .warning {
    background: var(--warning);
  }
  .running {
    background: var(--running);
  }
  .glow.success {
    box-shadow: 0 0 6px var(--success-glow);
  }
  .pulse {
    animation: pulse 2s cubic-bezier(0.4, 0, 0.6, 1) infinite;
  }
  @keyframes pulse {
    0%,
    100% {
      opacity: 1;
    }
    50% {
      opacity: 0.45;
    }
  }
</style>
