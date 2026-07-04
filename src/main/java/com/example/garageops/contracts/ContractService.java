package com.example.garageops.contracts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.garageops.garages.Garage;
import com.example.garageops.garages.GarageRepository;
import com.example.garageops.tenants.Tenant;
import com.example.garageops.tenants.TenantRepository;

import jakarta.persistence.EntityNotFoundException;

/**
 * Owns the contract lifecycle (S-04): create (with the overlap guard), end-early, and the read paths
 * the views render — a garage's rental history (FR-011), a tenant's contracts (FR-008), and the
 * portfolio "rented" derivation (FR-005).
 *
 * <p><b>Active/ended/upcoming is derived, never stored.</b> The overlap guard and {@link #rentedGarageIds}
 * both read {@link Contract#isActiveOn(LocalDate)} / the non-ended set — there is no stored status
 * flag. "Today" is {@code LocalDate.now()} at the call site for this slice; the injectable clock the
 * overdue engine needs (S-05) is deliberately not threaded here.
 *
 * <p>All three repositories are injected as {@link ObjectProvider}s and resolved per call, mirroring
 * {@code GarageService} / {@code LocationService}: this keeps the bean constructible in the DB-free
 * test context, where JPA autoconfig (and thus the repository beans) is excluded by convention. In
 * production the repositories are always present.
 */
@Service
public class ContractService {

	private final ObjectProvider<ContractRepository> contractRepository;
	private final ObjectProvider<TenantRepository> tenantRepository;
	private final ObjectProvider<GarageRepository> garageRepository;

	public ContractService(
			ObjectProvider<ContractRepository> contractRepository,
			ObjectProvider<TenantRepository> tenantRepository,
			ObjectProvider<GarageRepository> garageRepository) {
		this.contractRepository = contractRepository;
		this.tenantRepository = tenantRepository;
		this.garageRepository = garageRepository;
	}

	/**
	 * Create a contract linking a tenant to a garage (FR-009). Resolves and validates both parents
	 * (rejecting an archived tenant or garage), validates the term and payment-day bounds, then runs
	 * the overlap guard against the garage's existing non-ended contracts — rejecting any proposed
	 * window that intersects one, which is what keeps "≤ 1 active contract per garage" trustworthy
	 * (R6). On success a new {@link Contract} is saved and returned.
	 */
	@Transactional
	public Contract create(Long tenantId, Long garageId, LocalDate start, LocalDate plannedEnd,
			BigDecimal rent, int paymentDay) {
		Tenant tenant = requireTenant(tenantId);
		if (tenant.isArchived()) {
			throw new IllegalStateException("Cannot contract an archived tenant: " + tenantId);
		}
		Garage garage = requireGarage(garageId);
		if (garage.isArchived()) {
			throw new IllegalStateException("Cannot contract an archived garage: " + garageId);
		}
		if (plannedEnd.isBefore(start)) {
			throw new IllegalStateException("Planned end date cannot be before the start date");
		}
		if (paymentDay < 1 || paymentDay > 28) {
			throw new IllegalStateException("Payment day-of-month must be between 1 and 28");
		}
		rejectOverlap(garageId, start, plannedEnd);
		return contracts().save(new Contract(tenant, garage, start, plannedEnd, rent, paymentDay));
	}

	/** End a contract early on its actual move-out date (FR-010). The entity enforces the window. */
	@Transactional
	public void endEarly(Long contractId, LocalDate actualEnd) {
		Contract contract = requireContract(contractId);
		contract.endEarly(actualEnd);
		contracts().save(contract);
	}

	/** @return a garage's full rental history, newest first (FR-011); fetches the tenant for display. */
	public List<Contract> historyForGarage(Long garageId) {
		return contracts().findByGarageIdOrderByStartDateDesc(garageId);
	}

	/** @return all of a tenant's contracts, newest first (FR-008); fetches the garage for display. */
	public List<Contract> forTenant(Long tenantId) {
		return contracts().findByTenantIdOrderByStartDateDesc(tenantId);
	}

	/**
	 * @return the subset of {@code garageIds} that have a contract currently active on {@code today}.
	 *         Computed from one batch query over the non-ended contracts, filtered by
	 *         {@link Contract#isActiveOn(LocalDate)} so future-start contracts do not yet count as
	 *         "rented" — the batch path {@code LocationsView} uses for the portfolio status badge.
	 */
	public Set<Long> rentedGarageIds(List<Long> garageIds, LocalDate today) {
		if (garageIds.isEmpty()) {
			return Set.of();
		}
		return contracts().findNonEndedByGarageIdIn(garageIds).stream()
			.filter(c -> c.isActiveOn(today))
			.map(c -> c.getGarage().getId())
			.collect(Collectors.toSet());
	}

	// The proposed window overlaps an existing non-ended contract when the two date ranges intersect:
	// proposedStart ≤ existing.plannedEnd AND existing.start ≤ proposedPlannedEnd. The bounds are
	// inclusive, so two windows that touch on a single day (proposed start == existing planned end)
	// count as an overlap and are rejected.
	private void rejectOverlap(Long garageId, LocalDate start, LocalDate plannedEnd) {
		boolean overlaps = contracts().findByGarageIdAndEndedOnIsNull(garageId).stream()
			.anyMatch(existing -> !start.isAfter(existing.getPlannedEndDate())
				&& !existing.getStartDate().isAfter(plannedEnd));
		if (overlaps) {
			throw new IllegalStateException(
				"This garage already has a contract overlapping the requested dates");
		}
	}

	private Tenant requireTenant(Long id) {
		return tenants().findById(id)
			.orElseThrow(() -> new EntityNotFoundException("Unknown tenant: " + id));
	}

	private Garage requireGarage(Long id) {
		return garages().findById(id)
			.orElseThrow(() -> new EntityNotFoundException("Unknown garage: " + id));
	}

	private Contract requireContract(Long id) {
		return contracts().findById(id)
			.orElseThrow(() -> new EntityNotFoundException("Unknown contract: " + id));
	}

	private ContractRepository contracts() {
		return contractRepository.getObject();
	}

	private TenantRepository tenants() {
		return tenantRepository.getObject();
	}

	private GarageRepository garages() {
		return garageRepository.getObject();
	}
}
