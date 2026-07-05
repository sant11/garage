package com.example.garageops.payments;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractRepository;

/**
 * Derives the frequent-late-payer flag for one tenant (FR-020 / S-07) by re-running the pure
 * {@link OverdueRule} over each of the tenant's contracts' most-recent fully-due periods and counting
 * the overdue (contract, period) events against the configured threshold. No stored state, no schema
 * change — the verdict is computed live on profile load and returned as an off-session-safe
 * {@link LatePayerFlag}.
 *
 * <p><b>Why this can't reuse {@link OverdueService#duesAsOf}.</b> That path scans
 * {@code findActiveForOverdue()}, which filters to non-ended <em>and</em> non-archived contracts. A
 * tenant's late history must survive a contract ending or being archived (FR-021 retains it), so this
 * derivation loads <em>all</em> the tenant's contracts via
 * {@link ContractRepository#findByTenantIdOrderByStartDateDesc} and sums their payments with the
 * include-archived aggregation ({@link PaymentRepository#sumPaidByContractIdInPeriodIncludingArchived}).
 *
 * <p><b>Two guards against false positives.</b> (1) A period only counts for a contract that was in
 * effect on the period's due date ({@code startDate ≤ dueDate ≤ effectiveEnd}) — otherwise months
 * before the contract started or after it ended would each read as zero-paid and overdue. (2) The sum
 * includes archived payments, so a genuinely-paid archived period is not mistaken for non-payment.
 *
 * <p>The dependencies mirror {@link OverdueService}: the {@link OverdueRule} is a pure value held as a
 * plain field; the repositories are injected as {@link ObjectProvider}s resolved per call so the bean
 * stays constructible in the DB-free test context; the {@link Clock} is the fixed-zone application
 * bean (never {@code LocalDate.now()}).
 */
@Service
public class LatePayerService {

	private final OverdueRule rule = new OverdueRule();
	private final Clock clock;
	private final ObjectProvider<ContractRepository> contractRepository;
	private final ObjectProvider<PaymentRepository> paymentRepository;
	private final LatePayerProperties properties;

	public LatePayerService(
			Clock clock,
			ObjectProvider<ContractRepository> contractRepository,
			ObjectProvider<PaymentRepository> paymentRepository,
			LatePayerProperties properties) {
		this.clock = clock;
		this.contractRepository = contractRepository;
		this.paymentRepository = paymentRepository;
		this.properties = properties;
	}

	/**
	 * @return the late-payer verdict for {@code tenantId}: the count of overdue (contract, period)
	 *         events across the tenant's contracts in the last {@code windowMonths} fully-due periods,
	 *         flagged when that count reaches {@code minEvents}. Returns an unflagged zero-count verdict
	 *         for a tenant with no contracts or no payments.
	 */
	public LatePayerFlag evaluate(Long tenantId) {
		int windowMonths = properties.windowMonths();
		int minEvents = properties.minEvents();
		Instant now = clock.instant();
		ZoneId zone = clock.getZone();

		// Off-session read (spring.jpa.open-in-view=false, not @Transactional): the counting path below
		// touches only scalar Contract fields. findByTenantIdOrderByStartDateDesc fetch-joins c.garage but
		// NOT c.tenant — any future edit that traverses contract.getTenant()/getGarage() here must add the
		// matching JOIN FETCH to that finder or it will throw LazyInitializationException at runtime.
		List<Contract> contracts = contracts().findByTenantIdOrderByStartDateDesc(tenantId);

		// In-term (contract, period) candidates to judge, plus the contract ids touching each period.
		List<Candidate> candidates = new ArrayList<>();
		Map<YearMonth, List<Long>> idsByPeriod = new HashMap<>();
		for (Contract contract : contracts) {
			int day = contract.getPaymentDayOfMonth();
			int grace = contract.getGraceDays();
			LocalDate effectiveEnd =
				contract.getEndedOn() != null ? contract.getEndedOn() : contract.getPlannedEndDate();
			YearMonth p0 = rule.latestFullyDuePeriod(day, grace, now, zone);
			for (int back = 0; back < windowMonths; back++) {
				YearMonth period = p0.minusMonths(back);
				LocalDate due = rule.dueDate(period, day, grace);
				// Only count a period the contract was actually in effect for on its due date.
				if (contract.getStartDate().isAfter(due) || effectiveEnd.isBefore(due)) {
					continue;
				}
				candidates.add(new Candidate(contract, period, due));
				idsByPeriod.computeIfAbsent(period, p -> new ArrayList<>()).add(contract.getId());
			}
		}
		if (candidates.isEmpty()) {
			return new LatePayerFlag(false, 0, windowMonths, minEvents);
		}

		// One include-archived aggregation per distinct period (never one-per-contract), keyed by
		// (contractId, period) because a contract appears in several periods.
		Map<ContractPeriod, BigDecimal> paidByKey = new HashMap<>();
		idsByPeriod.forEach((period, ids) ->
			payments().sumPaidByContractIdInPeriodIncludingArchived(ids, period.atDay(1), period.atEndOfMonth())
				.forEach(sum -> paidByKey.put(new ContractPeriod(sum.getContractId(), period), sum.getPaidSum())));

		int eventCount = 0;
		for (Candidate candidate : candidates) {
			Contract contract = candidate.contract();
			Instant asOf = candidate.due().plusDays(1).atStartOfDay(zone).toInstant();
			BigDecimal paid = paidByKey.get(new ContractPeriod(contract.getId(), candidate.period()));
			// Transitional: pin the cumulative rule to the single candidate period (its due date as
			// the term start → exactly one period due) so the per-month aggregation above keeps its
			// old meaning; the cumulative-through-due-date sums replace this pinning.
			boolean overdue = rule.evaluate(contract.getMonthlyRent(), contract.getPaymentDayOfMonth(),
				contract.getGraceDays(), candidate.due(), null, paid, asOf, zone).overdue();
			if (overdue) {
				eventCount++;
			}
		}

		return new LatePayerFlag(eventCount >= minEvents, eventCount, windowMonths, minEvents);
	}

	private ContractRepository contracts() {
		return contractRepository.getObject();
	}

	private PaymentRepository payments() {
		return paymentRepository.getObject();
	}

	/** One in-term (contract, period) pair to judge, carrying its precomputed due date. */
	private record Candidate(Contract contract, YearMonth period, LocalDate due) {
	}

	/** Composite map key: a contract appears in several periods, so contractId alone won't do. */
	private record ContractPeriod(Long contractId, YearMonth period) {
	}
}
