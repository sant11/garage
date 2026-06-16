package com.example.garageops.payments;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractRepository;

import jakarta.persistence.EntityNotFoundException;

/**
 * Owns the payment write side (S-05): record a rent payment against a contract (FR-012), and
 * cascade-archive a contract's payments when the contract is archived (FR-021 retain-on-archive).
 *
 * <p>{@code record} resolves and validates the parent {@link Contract} (rejecting an archived one),
 * checks the amount and date, then persists a {@link Payment}. Bad input surfaces as
 * {@link IllegalStateException}, which the record-payment dialog catches and shows inline.
 *
 * <p>{@code archivePaymentsForContracts} is the cascade seam the garage/tenant/location archive paths
 * call after they stamp their contracts: it loads each contract's non-archived payments and stamps
 * them — a retain pass, never a delete, mirroring how those services stamp contracts. The overdue
 * aggregation excludes archived payments, so a cascade-stamped payment no longer counts toward a live
 * overdue sum while staying visible in history.
 *
 * <p>Both repositories are injected as {@link ObjectProvider}s and resolved per call, mirroring
 * {@code ContractService}: this keeps the bean constructible in the DB-free test context, where JPA
 * autoconfig (and thus the repository beans) is excluded by convention. In production the
 * repositories are always present.
 */
@Service
public class PaymentService {

	private final ObjectProvider<PaymentRepository> paymentRepository;
	private final ObjectProvider<ContractRepository> contractRepository;

	public PaymentService(
			ObjectProvider<PaymentRepository> paymentRepository,
			ObjectProvider<ContractRepository> contractRepository) {
		this.paymentRepository = paymentRepository;
		this.contractRepository = contractRepository;
	}

	/**
	 * Record a rent payment against a contract (FR-012). Resolves the contract (rejecting an archived
	 * one — you cannot post a payment to a retired contract), validates a strictly-positive amount and
	 * a non-null date, then saves and returns the new {@link Payment}.
	 */
	@Transactional
	public Payment record(Long contractId, BigDecimal amount, LocalDate date, String note) {
		Contract contract = requireContract(contractId);
		if (contract.isArchived()) {
			throw new IllegalStateException("Cannot record a payment against an archived contract: " + contractId);
		}
		if (amount == null || amount.signum() <= 0) {
			throw new IllegalStateException("Payment amount must be greater than zero");
		}
		if (date == null) {
			throw new IllegalStateException("Payment date is required");
		}
		return payments().save(new Payment(contract, amount, date, note));
	}

	/**
	 * Cascade-archive the non-archived payments of the given contracts (FR-021). Invoked from the
	 * garage/tenant/location archive cascades after they stamp the contracts themselves: loads the
	 * payments in one batch query, stamps each, and saves — never deletes. A no-op when there are no
	 * contract ids (the caller skips the query) or no live payments.
	 */
	@Transactional
	public void archivePaymentsForContracts(List<Long> contractIds) {
		if (contractIds.isEmpty()) {
			return;
		}
		List<Payment> activePayments = payments().findByContractIdInAndArchivedAtIsNull(contractIds);
		activePayments.forEach(Payment::archive);
		payments().saveAll(activePayments);
	}

	/**
	 * @return a contract's payment history, newest first (FR-014) — the per-contract history surface.
	 *         Renders only the payment's own fields (the contract is already in view context), so no
	 *         fetch is needed. Includes archived payments, which stay visible per FR-021.
	 */
	public List<Payment> historyForContract(Long contractId) {
		return payments().findByContractIdOrderByDateDesc(contractId);
	}

	/**
	 * @return a tenant's payment history across all their contracts, newest first (FR-014). The
	 *         repository {@code JOIN FETCH}es the garage so the off-session label column renders under
	 *         {@code open-in-view=false}. Includes archived payments, which stay visible per FR-021.
	 */
	public List<Payment> historyForTenant(Long tenantId) {
		return payments().findByTenantIdOrderByDateDesc(tenantId);
	}

	private Contract requireContract(Long id) {
		return contracts().findById(id)
			.orElseThrow(() -> new EntityNotFoundException("Unknown contract: " + id));
	}

	private PaymentRepository payments() {
		return paymentRepository.getObject();
	}

	private ContractRepository contracts() {
		return contractRepository.getObject();
	}
}
