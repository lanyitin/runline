-- Pipeline artifacts (ADR-003) and the definitions discovered in them (06-data-model).
-- An artifact is immutable and identified by the SHA-256 of its content.
CREATE TABLE pipeline_artifact (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    content_hash TEXT        NOT NULL UNIQUE CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    content      BYTEA       NOT NULL,
    size_bytes   BIGINT      NOT NULL CHECK (size_bytes >= 0),
    uploaded_by  TEXT        NOT NULL,
    uploaded_at  TIMESTAMPTZ NOT NULL
);

-- Deleting an artifact deletes its definitions; anything that references a definition
-- (triggers, runs: added by later migrations) must use ON DELETE RESTRICT so an artifact that
-- is in use cannot be deleted.
CREATE TABLE pipeline_definition (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    artifact_id             BIGINT  NOT NULL REFERENCES pipeline_artifact (id) ON DELETE CASCADE,
    class_name              TEXT    NOT NULL,
    name                    TEXT    NOT NULL,
    metadata                JSONB   NOT NULL,
    verdict                 TEXT    NOT NULL CHECK (verdict IN ('SAFE', 'UNSAFE')),
    reasons                 JSONB   NOT NULL,
    allow_list_version      TEXT    NOT NULL,
    -- Per-definition unsafe execution setting (ADR-006): never inherited, defaults to not allowed.
    allow_unsafe_execution  BOOLEAN NOT NULL DEFAULT FALSE,
    unsafe_setting_set_by   TEXT,
    UNIQUE (artifact_id, class_name),
    UNIQUE (artifact_id, name)
);
