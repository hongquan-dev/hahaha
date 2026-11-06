package com.fooddelivery.auth.repository;

import com.fooddelivery.auth.common.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Access to the {@code users} table. Query methods compare values exactly, so callers pass them in the stored form
 * (see {@code RegisterRequest.normalized()}).
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    // Login lookups (AuthService.login), by the identifier the user typed
    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    // First match: phone_number has no unique constraint, so a registration race could leave two rows
    Optional<User> findFirstByPhoneNumber(String phoneNumber);

    // Duplicate checks at registration (AuthService.register)
    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);
}
