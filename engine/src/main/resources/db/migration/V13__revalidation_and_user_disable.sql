-- Revoking access at once (WI-1205, ADR-029 decision 9).

-- An administrator can disable a person: their sessions go in the same transaction and they cannot
-- sign in again until they are enabled, whatever the provider says. Who last changed the state,
-- and when, is kept (it is also in the log).
ALTER TABLE oidc_user
    ADD COLUMN disabled           BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN status_changed_by  TEXT,
    ADD COLUMN status_changed_at  TIMESTAMPTZ;

-- A session has a handle of its own that an administrator can see and name to end it. It is not
-- the identifier of the session and opens nothing. The time of the last try to check a session
-- with the provider is kept apart from the last success: an outage is tried again once in an
-- interval, not by every request, and the grace time counts from the last success.
ALTER TABLE user_session
    ADD COLUMN public_id        UUID        NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN last_attempt_at  TIMESTAMPTZ;

CREATE UNIQUE INDEX user_session_public_id ON user_session (public_id);
