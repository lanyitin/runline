-- The import allow list that decides which pipelines are safe (WI-10, ADR-002, ADR-014).
-- An entry is a package (optionally "this package only") or a complete class; the kind is a column
-- of its own so the two are never told apart by looking at a name. The list as a whole has a
-- version: every change of the entries adds a row to allowlist_version, in the same transaction
-- that judges the stored definitions again. Pipeline definitions keep the version they were judged
-- under as text in pipeline_definition.allow_list_version, so definitions judged before this
-- table existed (version 'config') stay readable.
CREATE TABLE allowlist_version (
    version               BIGINT      PRIMARY KEY CHECK (version >= 1),
    changed_by            TEXT        NOT NULL,
    changed_at            TIMESTAMPTZ NOT NULL,
    action                TEXT        NOT NULL
                          CHECK (action IN ('INITIAL', 'ENTRY_ADDED', 'ENTRY_CHANGED', 'ENTRY_REMOVED')),
    detail                TEXT        NOT NULL,
    rejudged_definitions  INTEGER     NOT NULL DEFAULT 0 CHECK (rejudged_definitions >= 0),
    became_unsafe         INTEGER     NOT NULL DEFAULT 0 CHECK (became_unsafe >= 0),
    became_safe           INTEGER     NOT NULL DEFAULT 0 CHECK (became_safe >= 0)
);

CREATE TABLE allowlist_entry (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind        TEXT        NOT NULL CHECK (kind IN ('PACKAGE', 'CLASS')),
    name        TEXT        NOT NULL,
    exact_only  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_by  TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_by  TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    UNIQUE (kind, name),
    -- "This package only" has no meaning for a class.
    CHECK (kind = 'PACKAGE' OR NOT exact_only)
);
