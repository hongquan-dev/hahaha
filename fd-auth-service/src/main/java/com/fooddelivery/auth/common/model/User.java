package com.fooddelivery.auth.common.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Account of a person using the app, stored in the {@code users} table (created and updated by Hibernate,
 * {@code ddl-auto=update}).
 *
 * <p>The ID has no generated value: the caller assigns it with {@code IdUtils.generate(EntityType.USER)}.
 * {@code createdAt} and {@code updatedAt} are set by the JPA callbacks, never by the caller.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(name = "id", unique = true, nullable = false)
    private Long id;

    // Lowercase, e.g. "quan.nguyen"
    @Column(name = "username", unique = true, nullable = false)
    private String username;

    // BCrypt hash, e.g. "$2a$10$...", never the raw password
    @Column(name = "password", nullable = false)
    private String password;

    // Lowercase
    @Column(name = "email", unique = true)
    private String email;

    // As +84xxxxxxxxx. Not unique in the table; duplicates are rejected by AuthService
    @Column(name = "phone_number")
    private String phoneNumber;

    // e.g. "CUSTOMER". Set by the server, never taken from a request
    @Column(name = "role")
    private String role;

    @Column(name = "active", nullable = false, columnDefinition = "BOOLEAN DEFAULT TRUE")
    private boolean active = true;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public User() {}

    public User(Long id, String username, String password, String email, String phoneNumber, String role) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.email = email;
        this.phoneNumber = phoneNumber;
        this.role = role;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
