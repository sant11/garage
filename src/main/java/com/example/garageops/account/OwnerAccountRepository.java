package com.example.garageops.account;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access for the owner identity. {@code findByUsername} backs the DB-backed
 * {@link OwnerDetailsService}; {@code count} backs the idempotent {@link OwnerBootstrap}.
 */
public interface OwnerAccountRepository extends JpaRepository<OwnerAccount, Long> {

	Optional<OwnerAccount> findByUsername(String username);
}
