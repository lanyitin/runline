-- Sign-in with an OpenID Connect provider (WI-1200, ADR-029, 06-data-model of the login design):
-- the people who signed in, their sessions, and the sign-ins under way.

-- A person is told apart by the provider and the subject it gives them, never by name. The name is
-- what ownership is kept under everywhere else (ADR-012, ADR-020): it is made once, at the first
-- sign-in, and never changes, so that a rename at the provider does not take a person's pipelines
-- from them. It is unique, so that two people never share one. That it is not also the name of an
-- API token is checked by the Engine (the tokens are not in the database).
CREATE TABLE oidc_user (
    id            UUID        PRIMARY KEY,
    issuer        TEXT        NOT NULL,
    subject       TEXT        NOT NULL,
    name          TEXT        NOT NULL,
    display_name  TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    last_login_at TIMESTAMPTZ NOT NULL,
    UNIQUE (issuer, subject),
    UNIQUE (name)
);

-- The identifier of a session is only in the person's cookie; the database holds its SHA-256, which
-- cannot be turned back into it. The refresh token is only there sealed (AES-GCM, the key is not in
-- the database). A session is valid while now is before expires_at (the longest it may live) and
-- before last_used_at plus the idle time (a setting, so not stored); rows past either are removed
-- by the retention clean-up, but are not valid before it.
CREATE TABLE user_session (
    id_hash           BYTEA       PRIMARY KEY,
    user_id           UUID        NOT NULL REFERENCES oidc_user (id),
    role              TEXT        NOT NULL,
    csrf_token        TEXT        NOT NULL,
    refresh_token     BYTEA,
    created_at        TIMESTAMPTZ NOT NULL,
    last_used_at      TIMESTAMPTZ NOT NULL,
    last_validated_at TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX user_session_user ON user_session (user_id);
CREATE INDEX user_session_expires ON user_session (expires_at);
CREATE INDEX user_session_last_used ON user_session (last_used_at);

-- A sign-in between the person leaving for the provider and coming back: short-lived, used once.
CREATE TABLE login_attempt (
    state         TEXT        PRIMARY KEY,
    nonce         TEXT        NOT NULL,
    code_verifier TEXT        NOT NULL,
    return_path   TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE INDEX login_attempt_created ON login_attempt (created_at);
