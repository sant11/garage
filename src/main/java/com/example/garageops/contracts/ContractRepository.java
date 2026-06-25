package com.example.garageops.contracts;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access for {@link Contract}, covering the four read paths the service needs: the
 * per-garage rental history (FR-011), the per-tenant contract list (FR-008), the overlap guard, the
 * portfolio "rented" derivation, and the FR-021 archive cascade.
 *
 * <p>Finders whose result renders the {@code tenant} or {@code garage} association outside the
 * session {@code JOIN FETCH} it — {@code open-in-view=false}, and the service is not
 * {@code @Transactional} on read paths, so a LAZY association would otherwise throw
 * {@code LazyInitializationException} during view rendering.
 */
public interface ContractRepository extends JpaRepository<Contract, Long> {

	/** Full rental history for a garage (FR-011); fetches {@code tenant} for the name column. */
	@Query("select c from Contract c join fetch c.tenant "
			+ "where c.garage.id = :garageId order by c.startDate desc")
	List<Contract> findByGarageIdOrderByStartDateDesc(@Param("garageId") Long garageId);

	/** All contracts for a tenant (FR-008); fetches {@code garage} for the label/location column. */
	@Query("select c from Contract c join fetch c.garage "
			+ "where c.tenant.id = :tenantId order by c.startDate desc")
	List<Contract> findByTenantIdOrderByStartDateDesc(@Param("tenantId") Long tenantId);

	/** Non-ended contracts on a garage — feeds the overlap check (dates only, no fetch needed). */
	List<Contract> findByGarageIdAndEndedOnIsNull(Long garageId);

	/**
	 * Live contracts to scan for overdue dues (S-05): non-ended <em>and</em> non-archived. Fetches
	 * {@code garage} and {@code tenant} because the Dues view renders both labels off-session
	 * ({@code open-in-view=false}, no read-path transaction). The portfolio scan reuses this seam for
	 * S-06; ended/archived contracts are excluded so they never surface as currently overdue.
	 */
	@Query("select c from Contract c join fetch c.garage join fetch c.tenant "
			+ "where c.endedOn is null and c.archivedAt is null")
	List<Contract> findActiveForOverdue();

	/**
	 * Non-ended contracts across several garages in one query, to batch the portfolio "rented"
	 * derivation (no per-row query). Dates only, so no fetch needed.
	 */
	@Query("select c from Contract c where c.garage.id in :garageIds and c.endedOn is null")
	List<Contract> findNonEndedByGarageIdIn(@Param("garageIds") List<Long> garageIds);

	/** Non-archived contracts on a garage — the garage-archive cascade loops these to stamp them. */
	List<Contract> findByGarageIdAndArchivedAtIsNull(Long garageId);

	/** Non-archived contracts for a tenant — the tenant-archive cascade loops these to stamp them. */
	List<Contract> findByTenantIdAndArchivedAtIsNull(Long tenantId);

	/**
	 * Non-archived contracts across several garages in one query — the location-archive cascade
	 * stamps them in a single batch (the location already cascades to its garages, and each garage's
	 * contracts must be stamped too), avoiding a query-per-garage.
	 */
	@Query("select c from Contract c where c.garage.id in :garageIds and c.archivedAt is null")
	List<Contract> findNonArchivedByGarageIdIn(@Param("garageIds") List<Long> garageIds);

	/**
	 * Active, non-archived contracts whose {@code plannedEndDate} falls within {@code [from, to]}
	 * inclusive — the dashboard "ending soon" signal (S-06, FR-018). Already-ended ({@code endedOn}
	 * set) and archived contracts are excluded so a closed contract never surfaces as ending soon.
	 * Fetches {@code garage} and {@code tenant} because the dashboard renders both labels off-session
	 * ({@code open-in-view=false}, no read-path transaction); mirrors {@link #findActiveForOverdue()}.
	 */
	@Query("select c from Contract c join fetch c.garage join fetch c.tenant "
			+ "where c.endedOn is null and c.archivedAt is null "
			+ "and c.plannedEndDate between :from and :to order by c.plannedEndDate asc")
	List<Contract> findEndingBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);

	/**
	 * The most recent contract end date per garage, across the given garage ids — the batch the
	 * dashboard uses to derive how long each vacant garage has been empty without an N+1 per-garage
	 * scan (S-06). Projection-only (ids + dates), so no fetch is needed; follows the
	 * {@code PaymentRepository.ContractPaidSum} projection pattern. Garages with no ended contract are
	 * simply absent from the result (the caller falls back to the garage's creation date).
	 */
	@Query("select c.garage.id as garageId, max(c.endedOn) as lastEnded from Contract c "
			+ "where c.garage.id in :garageIds and c.endedOn is not null group by c.garage.id")
	List<GarageLastEnded> findLastEndedByGarageIdIn(@Param("garageIds") List<Long> garageIds);

	/** Projection for the per-garage most-recent end date: one {@code lastEnded} per garage id. */
	interface GarageLastEnded {
		Long getGarageId();

		LocalDate getLastEnded();
	}
}
