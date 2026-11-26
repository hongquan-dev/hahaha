-- Prepares the admin review of restaurants: rejection with a reason, who reviewed and when, and a history of every
-- review step. No API uses them yet; the Restaurant entity maps the new restaurants columns so validate passes.
-- Never edit this file once applied: Flyway checks its checksum

-- REJECTED: the admin refused the restaurant; the owner fixes it and submits again, back to PENDING
ALTER TABLE restaurants
    DROP CONSTRAINT ck_restaurants_status;
ALTER TABLE restaurants
    ADD CONSTRAINT ck_restaurants_status CHECK (status IN ('PENDING', 'ACTIVE', 'REJECTED', 'SUSPENDED'));

-- review_note: reason written by the admin, shown to the owner. submitted_at: last time the owner sent the restaurant
-- for review. reviewed_at / reviewed_by: last admin decision and the admin's users.id (auth-service, no foreign key)
ALTER TABLE restaurants
    ADD COLUMN review_note  VARCHAR(1000),
    ADD COLUMN submitted_at TIMESTAMP(6),
    ADD COLUMN reviewed_at  TIMESTAMP(6),
    ADD COLUMN reviewed_by  BIGINT;

-- Existing restaurants were sent for review when they were created
UPDATE restaurants
SET submitted_at = created_at
WHERE submitted_at IS NULL;

ALTER TABLE restaurants
    ALTER COLUMN submitted_at SET NOT NULL;

-- A rejection always tells the owner why; a review always records both when and by whom
ALTER TABLE restaurants
    ADD CONSTRAINT ck_restaurants_rejected_note CHECK (status <> 'REJECTED' OR review_note IS NOT NULL),
    ADD CONSTRAINT ck_restaurants_reviewed CHECK ((reviewed_at IS NULL) = (reviewed_by IS NULL));

-- One row per review step, never updated or deleted, so past rejections and their reasons stay visible after the
-- restaurant moves on. History starts with this migration: restaurants created before it have no SUBMITTED row.
-- id comes from IdUtils like every other table (needs a new EntityType when the first code writes to this table).
-- from_status is null for the first submission; actor_user_id is the owner for SUBMITTED, the admin otherwise
CREATE TABLE restaurant_reviews
(
    id            BIGINT        NOT NULL,
    restaurant_id BIGINT        NOT NULL,
    action        VARCHAR(32)   NOT NULL,
    from_status   VARCHAR(32),
    to_status     VARCHAR(32)   NOT NULL,
    note          VARCHAR(1000),
    actor_user_id BIGINT        NOT NULL,
    created_at    TIMESTAMP(6)  NOT NULL,
    CONSTRAINT pk_restaurant_reviews PRIMARY KEY (id),
    CONSTRAINT fk_restaurant_reviews_restaurant FOREIGN KEY (restaurant_id) REFERENCES restaurants (id),
    CONSTRAINT ck_restaurant_reviews_action
        CHECK (action IN ('SUBMITTED', 'APPROVED', 'REJECTED', 'SUSPENDED', 'REACTIVATED')),
    CONSTRAINT ck_restaurant_reviews_from_status
        CHECK (from_status IS NULL OR from_status IN ('PENDING', 'ACTIVE', 'REJECTED', 'SUSPENDED')),
    CONSTRAINT ck_restaurant_reviews_to_status
        CHECK (to_status IN ('PENDING', 'ACTIVE', 'REJECTED', 'SUSPENDED'))
);

-- History of one restaurant, newest first
CREATE INDEX idx_restaurant_reviews_restaurant_id ON restaurant_reviews (restaurant_id, created_at DESC);
