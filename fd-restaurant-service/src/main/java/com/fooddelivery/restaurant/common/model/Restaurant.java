package com.fooddelivery.restaurant.common.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * Restaurant run by a {@code RESTAURANT_OWNER} account, stored in the {@code restaurants} table (created by the Flyway
 * migrations in {@code db/migration}, checked by Hibernate with {@code ddl-auto=validate}).
 *
 * <p>The ID has no generated value: the caller assigns it with {@code IdUtils.generate(EntityType.RESTAURANT)}.
 * {@code createdAt} and {@code updatedAt} are set by the JPA callbacks, never by the caller. A schema change goes in a
 * new migration file, not only here.
 */
@Entity
@Table(name = "restaurants")
public class Restaurant {

    @Id
    @Column(name = "id", unique = true, nullable = false)
    private Long id;

    // users.id of the owner in auth-service, taken from the access token, never from a request body. Unique: one
    // restaurant per owner
    @Column(name = "owner_user_id", unique = true, nullable = false)
    private Long ownerUserId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "address", nullable = false, length = 255)
    private String address;

    // As +84xxxxxxxxx
    @Column(name = "phone_number", nullable = false, length = 16)
    private String phoneNumber;

    // null if the owner gave none
    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RestaurantStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Restaurant() {}

    public Restaurant(Long id, Long ownerUserId, String name, String address, String phoneNumber, String description,
                      RestaurantStatus status) {
        this.id = id;
        this.ownerUserId = ownerUserId;
        this.name = name;
        this.address = address;
        this.phoneNumber = phoneNumber;
        this.description = description;
        this.status = status;
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

    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long ownerUserId) { this.ownerUserId = ownerUserId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public RestaurantStatus getStatus() { return status; }
    public void setStatus(RestaurantStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
