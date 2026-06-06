package com.example.garageops.tenants;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityNotFoundException;

/**
 * Owns the tenant lifecycle (S-03): add, edit profile, list active, look up an active tenant for the
 * profile route, and <b>archive</b>.
 *
 * <p>Archive is the load-bearing operation: per FR-021 and the {@code ArchivableEntity} convention,
 * it stamps {@code archived_at} and <em>retains</em> the row — never deletes. Unlike
 * {@code LocationService}, a tenant has no children (no {@code @OneToMany}, the no-parent-collection
 * rule), so {@code archive()} is a single idempotent flag-flip with no cascade pass.
 *
 * <p>The repository is injected as an {@link ObjectProvider} and resolved per call, mirroring
 * {@code LocationService}: this keeps the bean constructible in the DB-free test context, where JPA
 * autoconfig (and thus the repository beans) is excluded by convention. In production the repository
 * is always present.
 */
@Service
public class TenantService {

	private final ObjectProvider<TenantRepository> tenantRepository;

	public TenantService(ObjectProvider<TenantRepository> tenantRepository) {
		this.tenantRepository = tenantRepository;
	}

	/** Add a new tenant. */
	public Tenant add(String name, String contactInfo) {
		return tenants().save(new Tenant(name, contactInfo));
	}

	/** Update an existing tenant's name and contact info. */
	public void editProfile(Long id, String name, String contactInfo) {
		Tenant tenant = require(id);
		tenant.editProfile(name, contactInfo);
		tenants().save(tenant);
	}

	/** @return active (non-archived) tenants, name-ordered, for the default tenants view. */
	public List<Tenant> listActive() {
		return tenants().findByArchivedAtIsNullOrderByNameAsc();
	}

	/**
	 * @return the active tenant for the profile route. Throws {@link EntityNotFoundException} when the
	 *         id is unknown or the tenant is archived, so the route can surface a 404 rather than
	 *         render a blank or partial profile.
	 */
	public Tenant findActive(Long id) {
		Tenant tenant = require(id);
		if (tenant.isArchived()) {
			throw new EntityNotFoundException("Unknown tenant: " + id);
		}
		return tenant;
	}

	/**
	 * Archive a tenant. Loads the tenant, stamps it, and saves — a retain operation, never a delete.
	 * {@code archive()} is idempotent, so a re-run leaves an existing archive moment intact. A tenant
	 * has no children, so there is no cascade pass.
	 */
	@Transactional
	public void archive(Long id) {
		Tenant tenant = require(id);
		tenant.archive();
		tenants().save(tenant);
	}

	private Tenant require(Long id) {
		return tenants().findById(id)
			.orElseThrow(() -> new EntityNotFoundException("Unknown tenant: " + id));
	}

	private TenantRepository tenants() {
		return tenantRepository.getObject();
	}
}
