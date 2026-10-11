-- A session needs the refresh token of its sign-in (WI-1207, ADR-030): it is how the provider is
-- asked again whether the person may still come in, so a session without one could never have its
-- access taken back. Such sessions are removed (the people stay, and sign in again; a sign-in that
-- gets no refresh token is refused from now on), and the column becomes required.
DELETE FROM user_session WHERE refresh_token IS NULL;

ALTER TABLE user_session ALTER COLUMN refresh_token SET NOT NULL;
