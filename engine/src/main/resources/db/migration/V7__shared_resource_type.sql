-- Typed shared resources (WI-40, ADR-019): a resource now has a type from a closed set, the
-- non-secret settings of that type, and the alias of its secret in the keystore. Only the alias is
-- ever stored; a secret value never enters the database.
--
-- Every resource defined before this migration becomes a `counter`, the type with exactly the
-- meaning resources always had (a name and a capacity), so nothing changes for them. The type is
-- fixed when the resource is created; there is no statement here or in the Engine that changes it.
ALTER TABLE shared_resource
    ADD COLUMN type         TEXT  NOT NULL DEFAULT 'counter'
        CONSTRAINT shared_resource_type_known
            CHECK (type IN ('counter', 'file', 'jdbc-pool', 'openai-compatible')),
    ADD COLUMN settings     JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN secret_alias TEXT;

-- Existing rows have been filled; from here on a resource is created with an explicit type.
ALTER TABLE shared_resource ALTER COLUMN type DROP DEFAULT;
ALTER TABLE shared_resource ALTER COLUMN settings DROP DEFAULT;
