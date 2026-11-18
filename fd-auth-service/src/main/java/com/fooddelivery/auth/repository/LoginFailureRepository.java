package com.fooddelivery.auth.repository;

import com.fooddelivery.auth.common.model.LoginFailure;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Access to the {@code login_failures} table, keyed by the SHA-256 identifier key of {@code LoginThrottleService}.
 */
@Repository
public interface LoginFailureRepository extends JpaRepository<LoginFailure, String> {

    /**
     * Creates the row of a key with no failure yet, or does nothing if it exists, in one statement, so two failed
     * logins with the same key at once cannot both try to insert it. Must run inside a transaction.
     *
     * @param identifierKey   SHA-256 key of the throttled identity
     * @param windowStartedAt start of the first window, normally now
     */
    @Modifying
    @Query(value = "INSERT INTO login_failures (identifier_key, failure_count, window_started_at) "
            + "VALUES (:identifierKey, 0, :windowStartedAt) ON CONFLICT (identifier_key) DO NOTHING",
            nativeQuery = true)
    void insertIfAbsent(@Param("identifierKey") String identifierKey,
                        @Param("windowStartedAt") Instant windowStartedAt);

    /**
     * Row of a key, locked ({@code SELECT ... FOR UPDATE}) until the transaction ends, so concurrent failures with
     * the same key are counted one after the other instead of overwriting each other. Must run inside a transaction.
     *
     * @param identifierKey SHA-256 key of the throttled identity
     * @return the locked row, empty if it does not exist
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select f from LoginFailure f where f.identifierKey = :identifierKey")
    Optional<LoginFailure> findByIdForUpdate(@Param("identifierKey") String identifierKey);

    /**
     * Deletes up to {@code batchSize} rows that neither lock nor count any more, in their own transaction: the lock,
     * if any, has ended and so has the counting window.
     *
     * @param now          current time; a lock ending at or before it has ended
     * @param windowCutoff now minus {@code auth.login.failure-window}; a window starting at or before it has ended
     * @param batchSize    maximum number of rows deleted by this call
     * @return number of rows deleted, {@code 0} when none is left
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM login_failures WHERE identifier_key IN (
                SELECT identifier_key FROM login_failures
                WHERE (locked_until IS NULL OR locked_until <= :now) AND window_started_at <= :windowCutoff
                LIMIT :batchSize)""", nativeQuery = true)
    int deleteExpired(@Param("now") Instant now, @Param("windowCutoff") Instant windowCutoff,
                      @Param("batchSize") int batchSize);
}
