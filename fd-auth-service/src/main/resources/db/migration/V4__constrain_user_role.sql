-- users.role becomes required and limited to the names of UserRole (common-dto-lib), stored as text by the User
-- entity with @Enumerated(STRING). A new role needs a new migration that replaces ck_users_role

-- Registration has only ever written CUSTOMER; anything else came from a manual edit and falls back to it
UPDATE users
SET role = 'CUSTOMER'
WHERE role IS NULL
   OR role NOT IN ('CUSTOMER', 'RESTAURANT_OWNER', 'DRIVER', 'ADMIN');

ALTER TABLE users
    ALTER COLUMN role TYPE VARCHAR(32),
    ALTER COLUMN role SET NOT NULL;

ALTER TABLE users
    ADD CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'RESTAURANT_OWNER', 'DRIVER', 'ADMIN'));
