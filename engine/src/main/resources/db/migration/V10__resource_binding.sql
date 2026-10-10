-- Resource roles and their bindings (WI-1012, ADR-025): a pipeline declares roles, and which
-- shared resource a role gets is chosen here, by uploader and pipeline name: kept across the
-- versions the same uploader made of the pipeline, and independent between uploaders, who cannot
-- see each other (ADR-020). A role with no row here gets the resource of the same name, which is
-- how every pipeline behaved before this migration, so nothing existing needs a row (this
-- migration has not been released, no environment holds bindings, and none is converted).
--
-- There is no foreign key to shared_resource: a resource may be deleted while a binding to it
-- stays (the run is then refused as `unknown`), and a binding may be set before the pipeline
-- version that declares the role exists.
CREATE TABLE resource_binding (
    uploader  TEXT        NOT NULL,
    pipeline  TEXT        NOT NULL,
    role      TEXT        NOT NULL,
    resource  TEXT        NOT NULL,
    set_by    TEXT        NOT NULL,
    set_at    TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (uploader, pipeline, role)
);

-- Who references a resource is asked from the resource's side.
CREATE INDEX resource_binding_resource ON resource_binding (resource);
