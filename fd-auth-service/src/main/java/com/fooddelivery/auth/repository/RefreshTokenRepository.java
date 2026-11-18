package com.fooddelivery.auth.repository;

import com.fooddelivery.auth.common.model.RefreshToken;
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
 * Access to the {@code refresh_tokens} table. Tokens are always looked up by their SHA-256 hash, never by the raw
 * value from the cookie.
 */
@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    // Token whose hash matches the cookie, revoked or not; the caller checks revokedAt and the expiry times
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Same as {@link #findByTokenHash}, but locks the row ({@code SELECT ... FOR UPDATE}) until the transaction ends.
     *
     * <p>Used by the refresh: of two requests carrying the same token, the second waits until the first has rotated
     * it, then sees it revoked, instead of both rotating it. Must run inside a transaction.
     *
     * @param tokenHash SHA-256 of the cookie value
     * @return the locked token, empty if no token has this hash
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RefreshToken t where t.tokenHash = :tokenHash")
    Optional<RefreshToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /**
     * Revokes every token of a family that is not revoked yet, in one {@code UPDATE}: ends one login session on
     * logout or when a reused token reveals a theft.
     *
     * <p>Must run inside a transaction. Tokens already revoked keep their original {@code revokedAt}.
     *
     * @param familyId  family to revoke, see {@link RefreshToken#getFamilyId()}
     * @param revokedAt time written to {@code revoked_at}
     * @return number of tokens revoked, {@code 0} if the family was already revoked
     */
    @Modifying
    @Query("update RefreshToken t set t.revokedAt = :revokedAt where t.familyId = :familyId and t.revokedAt is null")
    int revokeFamily(@Param("familyId") Long familyId, @Param("revokedAt") Instant revokedAt);

    /**
     * Deletes up to {@code batchSize} whole families that ended before {@code cutoff}, in their own transaction.
     *
     * <p>A token stops being usable at the earliest of its revocation (or its idle expiry if never revoked) and the
     * family's absolute expiry; a family ends when its last token stops. A live family always has a usable token, so
     * the replaced tokens that reveal a reused (stolen) token are never deleted while they matter.
     *
     * @param cutoff    families that ended before this time are deleted, e.g. now minus the retention
     * @param batchSize maximum number of families deleted by this call
     * @return number of tokens deleted, {@code 0} when no family is left to delete
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM refresh_tokens WHERE family_id IN (
                SELECT family_id FROM refresh_tokens
                GROUP BY family_id
                HAVING max(LEAST(family_expires_at, COALESCE(revoked_at, expires_at))) < :cutoff
                LIMIT :batchSize)""", nativeQuery = true)
    int deleteFamiliesEndedBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
