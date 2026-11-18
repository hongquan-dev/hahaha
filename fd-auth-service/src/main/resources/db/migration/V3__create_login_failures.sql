-- Failed logins per account or per typed identifier, read by LoginThrottleService to lock out password guessing.
-- identifier_key is a SHA-256 hex, never the identifier itself: what was typed may be a password in the wrong field.
-- Times are TIMESTAMPTZ because the entity uses Instant
CREATE TABLE login_failures
(
    identifier_key    VARCHAR(64)    NOT NULL,
    failure_count     INTEGER        NOT NULL,
    window_started_at TIMESTAMPTZ(6) NOT NULL,
    locked_until      TIMESTAMPTZ(6),
    CONSTRAINT pk_login_failures PRIMARY KEY (identifier_key)
);
