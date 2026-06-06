package com.example.garageops.tenants;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access for {@link Tenant}. The active-only finder backs the default tenants view;
 * archived rows stay reachable via the inherited {@code findById}/{@code findAll} for future history.
 */
public interface TenantRepository extends JpaRepository<Tenant, Long> {

	List<Tenant> findByArchivedAtIsNullOrderByNameAsc();
}
