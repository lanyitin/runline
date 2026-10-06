-- Triggers (WI-07, ADR-005): what an administrator binds to a pipeline definition so that runs are
-- created on a schedule (cron) or when a webhook is called.
--
-- A trigger refers to its definition with ON DELETE RESTRICT: deleting an artifact cascades to its
-- definitions, so a definition a trigger is bound to makes the artifact undeletable until the
-- trigger is deleted (unbound). The binding is to one definition, that is one version; it never
-- follows a newer one.
CREATE TABLE pipeline_trigger (
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name               TEXT        NOT NULL UNIQUE CHECK (name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'),
    kind               TEXT        NOT NULL CHECK (kind IN ('CRON', 'WEBHOOK')),
    definition_id      BIGINT      NOT NULL REFERENCES pipeline_definition (id) ON DELETE RESTRICT,
    -- The fixed parameters a run gets: validated against the definition's metadata, defaults applied.
    parameters         JSONB       NOT NULL,
    enabled            BOOLEAN     NOT NULL,
    -- Cron only: a standard five-field expression read in an IANA time zone.
    cron_expression    TEXT,
    time_zone          TEXT,
    -- Webhook only: the SHA-256 (hex) of the secret. The secret itself is never stored.
    secret_hash        TEXT,
    secret_rotated_at  TIMESTAMPTZ,
    created_by         TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_by         TEXT        NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT trigger_kind_columns CHECK (
        (kind = 'CRON' AND cron_expression IS NOT NULL AND time_zone IS NOT NULL
            AND secret_hash IS NULL AND secret_rotated_at IS NULL)
        OR
        (kind = 'WEBHOOK' AND cron_expression IS NULL AND time_zone IS NULL
            AND secret_hash IS NOT NULL AND secret_rotated_at IS NOT NULL))
);

CREATE INDEX pipeline_trigger_definition_idx ON pipeline_trigger (definition_id);

-- Every time a trigger went off and what came of it: the administrator's record of firings, and the
-- uniqueness that keeps one firing from creating two runs.
--   * webhook: (trigger, delivery_id) is unique, so a delivery that is sent again, even at the same
--     moment, is one firing;
--   * cron: (trigger, scheduled_for) is unique, so one scheduled time fires once.
-- Only firings of an authenticated webhook call and of a cron occurrence are recorded; calls that
-- fail authentication are not, so that nobody without the secret can make this table grow.
-- The table has no retention yet (WI-20 covers runs and their log only): it grows by one row per
-- cron occurrence and per accepted delivery.
CREATE TABLE trigger_firing (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    trigger_id     BIGINT      NOT NULL REFERENCES pipeline_trigger (id) ON DELETE CASCADE,
    fired_at       TIMESTAMPTZ NOT NULL,
    delivery_id    TEXT,
    scheduled_for  TIMESTAMPTZ,
    outcome        TEXT        NOT NULL CHECK (outcome IN
        ('PENDING', 'RUN_CREATED', 'REFUSED', 'FAILED', 'INTERRUPTED')),
    -- Why a firing was refused or failed (a stable code), and a sentence for the administrator.
    reason         TEXT,
    detail         TEXT,
    -- A run that is cleaned up later leaves the record, without the run.
    run_id         UUID        REFERENCES run (id) ON DELETE SET NULL,
    UNIQUE (trigger_id, delivery_id),
    UNIQUE (trigger_id, scheduled_for)
);

CREATE INDEX trigger_firing_recent_idx ON trigger_firing (trigger_id, fired_at DESC, id DESC);
