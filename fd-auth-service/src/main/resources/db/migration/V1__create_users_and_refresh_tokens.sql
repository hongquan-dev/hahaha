-- Initial schema of fd-auth-service, run by Flyway at startup. Column types match what Hibernate expects for the
-- User and RefreshToken entities, so spring.jpa.hibernate.ddl-auto=validate passes.
-- Never edit this file once applied: Flyway checks its checksum. Change the schema with a new V2__..., V3__... file

-- IDs come from IdUtils.generate(EntityType.USER), never from the database, hence no identity or sequence
CREATE TABLE users
(
    id           BIGINT       NOT NULL,
    username     VARCHAR(255) NOT NULL,
    password     VARCHAR(255) NOT NULL,
    email        VARCHAR(255),
    phone_number VARCHAR(255),
    role         VARCHAR(255),
    active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP(6),
    updated_at   TIMESTAMP(6),
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email UNIQUE (email)
);

-- Times are TIMESTAMPTZ because the entity uses Instant. user_id has no foreign key, as in the entity: a plain column
CREATE TABLE refresh_tokens
(
    id                BIGINT         NOT NULL,
    user_id           BIGINT         NOT NULL,
    token_hash        VARCHAR(64)    NOT NULL,
    family_id         BIGINT         NOT NULL,
    expires_at        TIMESTAMPTZ(6) NOT NULL,
    family_expires_at TIMESTAMPTZ(6) NOT NULL,
    revoked_at        TIMESTAMPTZ(6),
    replaced_at       TIMESTAMPTZ(6),
    user_agent        VARCHAR(512),
    ip                VARCHAR(45),
    created_at        TIMESTAMPTZ(6) NOT NULL,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
