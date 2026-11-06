package com.fooddelivery.auth.common.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Refresh token of one login session, stored in the {@code refresh_tokens} table (created and updated by Hibernate,
 * {@code ddl-auto=update}). Only its SHA-256 hash is stored; the raw token lives in the {@code refresh_token} cookie.
 *
 * <p>Every refresh revokes the current row and adds a new one with the same {@code familyId}, so a family is the
 * chain of tokens of one login on one device. The ID has no generated value: the caller assigns it with
 * {@code IdUtils.generate(EntityType.REFRESH_TOKEN)}. Times are UTC {@link Instant}s, unlike {@link User}, because
 * expiry checks and cookie {@code Max-Age} must not depend on the server or database time zone. {@code createdAt} is
 * set by the JPA callback, never by the caller.
 */
@Entity
@Table(
        name = "refresh_tokens",
        uniqueConstraints = @UniqueConstraint(name = "uk_refresh_tokens_token_hash", columnNames = "token_hash"),
        indexes = {
                @Index(name = "idx_refresh_tokens_family_id", columnList = "family_id"),
                @Index(name = "idx_refresh_tokens_user_id", columnList = "user_id")
        })
public class RefreshToken {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    // ID of the owning User. A plain column, not a relation, so a refresh never loads the user entity it does not need
    @Column(name = "user_id", nullable = false)
    private Long userId;

    // SHA-256 of the raw token as 64 lowercase hex characters. SHA-256 rather than BCrypt: the token is 256 random
    // bits, so it cannot be brute-forced, and an unsalted hash can be looked up by index
    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    // ID of the first token of the family, the one created at login; that token has familyId == id
    @Column(name = "family_id", nullable = false)
    private Long familyId;

    // Idle expiry of this token: creation + auth.refresh.idle-ttl (7 days)
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    // Absolute expiry of the whole family: login + auth.refresh.absolute-ttl (30 days), copied unchanged to every
    // token of the family
    @Column(name = "family_expires_at", nullable = false)
    private Instant familyExpiresAt;

    // null while the token can be used. Set on rotation, logout or when the family is revoked
    @Column(name = "revoked_at")
    private Instant revokedAt;

    // When a refresh replaced this token with a new one, null otherwise. A reuse within auth.refresh.reuse-grace of
    // this time is a race between tabs, not a theft
    @Column(name = "replaced_at")
    private Instant replacedAt;

    // User-Agent header of the request that created the token, truncated to 512 characters; null if absent
    @Column(name = "user_agent", length = 512)
    private String userAgent;

    // Client IP of the request that created the token, IPv4 or IPv6 (up to 45 characters)
    @Column(name = "ip", length = 45)
    private String ip;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public RefreshToken() {}

    public RefreshToken(Long id, Long userId, String tokenHash, Long familyId, Instant expiresAt,
                        Instant familyExpiresAt, String userAgent, String ip) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.familyId = familyId;
        this.expiresAt = expiresAt;
        this.familyExpiresAt = familyExpiresAt;
        this.userAgent = userAgent;
        this.ip = ip;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public Long getFamilyId() { return familyId; }
    public void setFamilyId(Long familyId) { this.familyId = familyId; }

    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }

    public Instant getFamilyExpiresAt() { return familyExpiresAt; }
    public void setFamilyExpiresAt(Instant familyExpiresAt) { this.familyExpiresAt = familyExpiresAt; }

    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }

    public Instant getReplacedAt() { return replacedAt; }
    public void setReplacedAt(Instant replacedAt) { this.replacedAt = replacedAt; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }

    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
