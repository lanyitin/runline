-- The last check of a shared resource's entity (WI-43, ADR-019): whether it passed, the category of
-- the failure if not, and when. Nothing more is kept: no path, no detail, no secret. A resource that
-- was never checked, or whose settings or secret alias changed since, has none of the three.
ALTER TABLE shared_resource
    ADD COLUMN check_ok      BOOLEAN,
    ADD COLUMN check_failure TEXT,
    ADD COLUMN checked_at    TIMESTAMPTZ,
    ADD CONSTRAINT shared_resource_check_consistent CHECK (
        (check_ok IS NULL) = (checked_at IS NULL)
        AND (check_ok IS DISTINCT FROM TRUE OR check_failure IS NULL)
        AND (check_ok IS DISTINCT FROM FALSE OR check_failure IS NOT NULL)
    );
