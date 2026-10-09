package com.forgeflow.account;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /** Invites: someone typing "Aman@Example.com" means the same person. */
    Optional<User> findByEmailIgnoreCaseAndDeletedAtIsNull(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailIgnoreCase(String email);
}
