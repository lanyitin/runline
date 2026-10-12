-- Parameters the caller of a webhook provides in the body of each call (WI-1400, ADR-036): names of
-- parameters of the bound version, as a JSON array of text. Empty means the body is never read.
-- A cron trigger has no caller, so the list of a cron trigger is always empty.
ALTER TABLE pipeline_trigger
    ADD COLUMN caller_parameters JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT trigger_caller_parameters_webhook_only
        CHECK (kind = 'WEBHOOK' OR caller_parameters = '[]'::jsonb);
