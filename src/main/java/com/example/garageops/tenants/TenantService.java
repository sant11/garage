package com.example.garageops.tenants;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractRepository;

import jakarta.persistence.EntityNotFoundException;

/**
 * Owns the tenant lifecycle (S-03): add, edit profile, list active, look up an active tenant for the
 * profile route, and <b>archive</b>.
 *
 * <p>Archive is the load-bearing operation: per FR-021 and the {@code ArchivableEntity} convention,
 * it stamps {@code archived_at} and <em>retains</em> the row — never deletes. A tenant has no
 * {@code @OneToMany} children (the no-parent-collection rule), but it does own contracts on the FK
 * child side, so {@code archive} cascade-stamps the tenant's active contracts via an explicit
 * loop-and-stamp pass (mirroring {@code LocationService}) — never a JPA cascade, never a delete.
 *
 * <p>The repositories are injected as {@link ObjectProvider}s and resolved per call, mirroring
 * {@code LocationService}: this keeps the bean constructible in the DB-free test context, where JPA
 * autoconfig (and thus the repository beans) is excluded by convention. In production the
 * repositories are always present.
 */
@Service
public class TenantService {

	private final ObjectProvider<TenantRepository> tenantRepository;
	private final ObjectProvider<ContractRepository> contractRepository;

	public TenantService(
			ObjectProvider<TenantRepository> tenantRepository,
			ObjectProvider<ContractRepository> contractRepository) {
		this.tenantRepository = tenantRepository;
		this.contractRepository = contractRepository;
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
	 * Archive a tenant and cascade-stamp its active contracts (FR-021). Loads the tenant, stamps it,
	 * then loads its non-archived contracts and stamps each — a retain pass, never a delete.
	 * {@code archive()} is idempotent, so a re-run leaves existing archive moments intact.
	 */
	@Transactional
	public void archive(Long id) {
		Tenant tenant = require(id);
		tenant.archive();
		tenants().save(tenant);

		List<Contract> activeContracts = contracts().findByTenantIdAndArchivedAtIsNull(id);
		activeContracts.forEach(Contract::archive);
		contracts().saveAll(activeContracts);
	}

	private Tenant require(Long id) {
		return tenants().findById(id)
			.orElseThrow(() -> new EntityNotFoundException("Unknown tenant: " + id));
	}

	private TenantRepository tenants() {
		return tenantRepository.getObject();
	}

	private ContractRepository contracts() {
		return contractRepository.getObject();
	}
}
