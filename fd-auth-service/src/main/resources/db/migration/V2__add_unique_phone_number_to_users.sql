-- Phone numbers are unique like usernames and emails. AuthService already rejects a duplicate at registration; this
-- constraint also stops two concurrent registrations with the same number, which both pass that check before either
-- is saved. Fails if the table already holds a duplicate number: remove it first. NULLs stay allowed, several times
ALTER TABLE users
    ADD CONSTRAINT uk_users_phone_number UNIQUE (phone_number);
