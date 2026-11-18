package com.fooddelivery.auth.common.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Failed logins of one account or one typed identifier, stored in the {@code login_failures} table (created by the
 * Flyway migrations in {@code db/migration}, checked by Hibernate with {@code ddl-auto=validate}).
 *
 * <p>Read and updated by {@code LoginThrottleService} only: a row is created by the first failure, counts failures
 * within a window, and is deleted by a successful login. Times are UTC {@link Instant}s, like {@link RefreshToken}.
 */
@Entity
@Table(name = "login_failures")
public class LoginFailure {

    // SHA-256 of the throttled identity as 64 lowercase hex characters, see LoginThrottleService.keyFor. Never the
    // identifier itself, which may be a password typed in the wrong field
    @Id
    @Column(name = "identifier_key", nullable = false, length = 64)
    private String identifierKey;

    // Failures since windowStartedAt; back to 0 when the window ends or a lock starts
    @Column(name = "failure_count", nullable = false)
    private int failureCount;

    @Column(name = "window_started_at", nullable = false)
    private Instant windowStartedAt;

    // Logins with this key are refused until this time; null if not locked
    @Column(name = "locked_until")
    private Instant lockedUntil;

    public LoginFailure() {}

    public String getIdentifierKey() {
        return identifierKey;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public Instant getWindowStartedAt() {
        return windowStartedAt;
    }

    public void setWindowStartedAt(Instant windowStartedAt) {
        this.windowStartedAt = windowStartedAt;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public void setLockedUntil(Instant lockedUntil) {
        this.lockedUntil = lockedUntil;
    }
}
