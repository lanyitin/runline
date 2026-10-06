-- Indexes for the retention clean-up (WI-20). The clean-up removes expired rows in small batches,
-- always looking for "the oldest ones that are past their limit", so each query reads an index in
-- order from its oldest end and stops after one batch instead of scanning the table.

-- Runs that have ended, by when they ended. A run that has not ended has no end time and is not in
-- the index, so it can never be picked.
CREATE INDEX run_finished_idx ON run (finished_at) WHERE finished_at IS NOT NULL;

-- Firings by when they happened, one index each for webhook and cron because their limits differ,
-- without the pending ones, which the clean-up never removes.
CREATE INDEX trigger_firing_webhook_expiry_idx ON trigger_firing (fired_at)
    WHERE delivery_id IS NOT NULL AND outcome <> 'PENDING';
CREATE INDEX trigger_firing_cron_expiry_idx ON trigger_firing (fired_at)
    WHERE scheduled_for IS NOT NULL AND outcome <> 'PENDING';

-- Removing a run sets run_id to NULL in the firings that refer to it (ON DELETE SET NULL); without
-- this index each removed run would read the whole of trigger_firing to find them.
CREATE INDEX trigger_firing_run_idx ON trigger_firing (run_id) WHERE run_id IS NOT NULL;
