package com.example.garageops.payments;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access for {@link Payment}, covering the two read paths S-05 needs: the per-contract
 * and per-tenant payment history surfaces (FR-014), and the batch per-period paid-sum that feeds the
 * overdue rule (FR-013) — the codebase's first {@code SUM ... GROUP BY}.
 *
 * <p>The per-tenant finder renders the {@code garage} label off-session, so it {@code JOIN FETCH}es
 * {@code contract} and {@code garage} — {@code open-in-view=false} and the read paths are not
 * {@code @Transactional}, so a LAZY association would otherwise throw
 * {@code LazyInitializationException} during view rendering. The per-contract finder renders only
 * the payment's own fields (the contract is already in context), so it needs no fetch.
 *
 * <p>History finders retain archived payments (FR-021 visible-on-archive); the aggregation excludes
 * archived payments so a cascade-archived payment never counts toward a live overdue sum.
 */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

	/** A contract's payment history, newest first (FR-014); payment fields only, no fetch needed. */
	List<Payment> findByContractIdOrderByDateDesc(Long contractId);

	/**
	 * Non-archived payments across several contracts — the contract-archive cascade loops these to
	 * stamp them (FR-021 retain-on-archive), so payments on an archived contract are retained yet no
	 * longer count toward a live overdue sum. Batched over the contract ids so a multi-contract
	 * archive (garage / tenant / location) stamps in one query rather than one per contract.
	 */
	List<Payment> findByContractIdInAndArchivedAtIsNull(List<Long> contractIds);

	/** A tenant's payment history, newest first (FR-014); fetches the garage for the label column. */
	@Query("select p from Payment p join fetch p.contract c join fetch c.garage "
			+ "where c.tenant.id = :tenantId order by p.date desc")
	List<Payment> findByTenantIdOrderByDateDesc(@Param("tenantId") Long tenantId);

	/**
	 * Per-contract sum of non-archived payment {@code amount} whose {@code date} falls within
	 * {@code [periodStart, periodEnd]}, for the given contract ids — the single batch aggregation the
	 * overdue/portfolio scan runs instead of a query per contract (avoids N+1; the seam S-06 reuses).
	 * Contracts with no qualifying payment are simply absent from the result (treated as paid-zero by
	 * the caller).
	 */
	@Query("select p.contract.id as contractId, sum(p.amount) as paidSum from Payment p "
			+ "where p.contract.id in :contractIds and p.date between :periodStart and :periodEnd "
			+ "and p.archivedAt is null group by p.contract.id")
	List<ContractPaidSum> sumPaidByContractIdInPeriod(@Param("contractIds") List<Long> contractIds,
			@Param("periodStart") LocalDate periodStart, @Param("periodEnd") LocalDate periodEnd);

	/** Projection for the batch paid-sum aggregation: one summed amount per contract id. */
	interface ContractPaidSum {
		Long getContractId();

		BigDecimal getPaidSum();
	}
}
