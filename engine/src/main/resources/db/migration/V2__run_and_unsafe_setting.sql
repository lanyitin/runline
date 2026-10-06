-- Runs and their log (WI-08, 06-data-model), and the time of the unsafe execution setting that
-- V1 already records per definition (allow_unsafe_execution, unsafe_setting_set_by).
ALTER TABLE pipeline_definition ADD COLUMN unsafe_setting_set_at TIMESTAMPTZ;

-- A run refers to its definition with ON DELETE RESTRICT: deleting an artifact cascades to its
-- definitions, so a definition that a run refers to makes the artifact undeletable.
CREATE TABLE run (
    id                     UUID        PRIMARY KEY,
    definition_id          BIGINT      NOT NULL REFERENCES pipeline_definition (id) ON DELETE RESTRICT,
    state                  TEXT        NOT NULL CHECK (state IN (
        'QUEUED', 'WAITING_FOR_RESOURCES', 'INITIALIZING', 'RUNNING', 'TIMED_OUT_UNFINISHED',
        'SUCCEEDED', 'FAILED', 'CANCELLED', 'INTERRUPTED', 'TIMED_OUT')),
    -- Who or what caused the run: a person calling the API (name of the token) or a trigger.
    source_kind            TEXT        NOT NULL CHECK (source_kind IN ('MANUAL', 'TRIGGER')),
    source_name            TEXT        NOT NULL,
    parameters             JSONB       NOT NULL,
    created_at             TIMESTAMPTZ NOT NULL,
    started_at             TIMESTAMPTZ,
    finished_at            TIMESTAMPTZ,
    failure_type           TEXT,
    failure_message        TEXT,
    failure_trace          TEXT,
    -- The unsafe execution setting in force when an unsafe pipeline was started.
    unsafe_execution       BOOLEAN     NOT NULL DEFAULT FALSE,
    unsafe_setting_set_by  TEXT,
    unsafe_setting_set_at  TIMESTAMPTZ
);

CREATE INDEX run_definition_idx ON run (definition_id);
CREATE INDEX run_created_idx ON run (created_at DESC, id);

-- A run's log; seq starts at 1 and grows by one within a run.
CREATE TABLE run_log_entry (
    run_id     UUID        NOT NULL REFERENCES run (id) ON DELETE CASCADE,
    seq        BIGINT      NOT NULL CHECK (seq >= 1),
    logged_at  TIMESTAMPTZ NOT NULL,
    stream     TEXT        NOT NULL CHECK (stream IN ('STDOUT', 'STDERR')),
    line       TEXT        NOT NULL,
    PRIMARY KEY (run_id, seq)
);
