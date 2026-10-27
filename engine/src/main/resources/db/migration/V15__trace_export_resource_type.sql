-- The fifth resource type, `trace-export` (ADR-46, #51): spans a pipeline hands over are exported
-- to an OTLP endpoint with a credential of the keystore. The set of types stays closed; the
-- constraint of V7 is replaced by the same one with one more member. Nothing else changes: the
-- type is still fixed when a resource is created, and only the alias of the credential is stored.
ALTER TABLE shared_resource DROP CONSTRAINT shared_resource_type_known;
ALTER TABLE shared_resource
    ADD CONSTRAINT shared_resource_type_known
        CHECK (type IN ('counter', 'file', 'jdbc-pool', 'openai-compatible', 'trace-export'));
