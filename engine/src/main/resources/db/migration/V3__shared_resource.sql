-- Shared resources (WI-09, ADR-007): the definitions an administrator maintains. Who holds a
-- resource or waits for it is runtime state of the Engine and is deliberately not stored.
-- Pipelines declare resources by name inside their metadata, and a pipeline may be uploaded
-- before its resource is defined (the upload only warns), so there is no foreign key to
-- pipeline_definition and nothing here to make an artifact undeletable.
CREATE TABLE shared_resource (
    name        TEXT        PRIMARY KEY CHECK (name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'),
    capacity    INTEGER     NOT NULL CHECK (capacity >= 1),
    enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by  TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_by  TEXT        NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL
);
