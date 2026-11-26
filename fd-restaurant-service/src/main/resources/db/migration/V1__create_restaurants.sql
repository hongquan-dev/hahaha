-- Initial schema of fd-restaurant-service, run by Flyway at startup. Column types match what Hibernate expects for the
-- Restaurant entity, so spring.jpa.hibernate.ddl-auto=validate passes.
-- Never edit this file once applied: Flyway checks its checksum. Change the schema with a new V2__..., V3__... file

-- IDs come from IdUtils.generate(EntityType.RESTAURANT), never from the database, hence no identity or sequence.
-- owner_user_id is a users.id of auth-service, in another database, so it has no foreign key; it is unique because an
-- owner has one restaurant. status takes the names of RestaurantStatus
CREATE TABLE restaurants
(
    id            BIGINT        NOT NULL,
    owner_user_id BIGINT        NOT NULL,
    name          VARCHAR(100)  NOT NULL,
    address       VARCHAR(255)  NOT NULL,
    phone_number  VARCHAR(16)   NOT NULL,
    description   VARCHAR(1000),
    status        VARCHAR(32)   NOT NULL,
    created_at    TIMESTAMP(6)  NOT NULL,
    updated_at    TIMESTAMP(6)  NOT NULL,
    CONSTRAINT pk_restaurants PRIMARY KEY (id),
    CONSTRAINT uk_restaurants_owner_user_id UNIQUE (owner_user_id),
    CONSTRAINT ck_restaurants_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED'))
);
