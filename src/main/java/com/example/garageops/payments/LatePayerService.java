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
 * the late (contract, period) events against the configured threshold. A period is a late event iff
 * the contract's cumulative balance was negative the day after that period's due date — judged by
 * summing every payment dated on/before the due date against all rent owed by then. No stored state,
 * no schema change — the verdict is computed live on profile load and returned as an
 * off-session-safe {@link LatePayerFlag}.
 *
 * <p><b>Late-but-eventually-paid still counts.</b> A rent settled after its due date leaves the
 * event on record even once arrears clear — the flag surfaces habitual lateness, not current debt
 * (Dues covers that). Conversely, persistent arrears <em>cascade</em>: because payments credit the
 * oldest debt first (FIFO, no period attribution on {@code Payment}), one skipped month that is
 * never caught up leaves the balance negative at every subsequent due date, producing an event per
 * period until it is settled. Both are intended semantics.
 *
 * <p><b>Why this can't reuse {@link OverdueService#duesAsOf}.</b> That path scans
 * {@code findNonArchivedForOverdue()}, which excludes archived contracts, and sums only non-archived
 * payments. A tenant's late history must survive a contract being archived (FR-021 retains it), so
 * this derivation loads <em>all</em> the tenant's contracts via
 * {@link ContractRepository#findByTenantIdOrderByStartDateDesc} and sums their payments with the
 * include-archived aggregation ({@link PaymentRepository#sumPaidThroughDateByContractIdIn}).
 *
 * <p><b>Two guards against false positives.</b> (1) A period only counts for a contract that was in
 * effect on the period's due date ({@code startDate ≤ dueDate ≤ effectiveEnd}) — otherwise months
 * before the contract started or after it ended would each read as zero-paid and overdue. (2) The sum
 * includes archived payments, so a genuinely-paid archived history is not mistaken for non-payment.
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
	 * @return the late-payer verdict for {@code tenantId}: the count of (contract, period) events —
	 *         periods whose due date passed with the contract's cumulative balance still negative the
	 *         next day — across the tenant's contracts in the last {@code windowMonths} fully-due
	 *         periods, flagged when that count reaches {@code minEvents}. Returns an unflagged
	 *         zero-count verdict for a tenant with no contracts or no payments.
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

		// In-term (contract, period) candidates to judge, plus the contract ids sharing each due date.
		List<Candidate> candidates = new ArrayList<>();
		Map<LocalDate, List<Long>> idsByDueDate = new HashMap<>();
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
				candidates.add(new Candidate(contract, effectiveEnd, due));
				idsByDueDate.computeIfAbsent(due, d -> new ArrayList<>()).add(contract.getId());
			}
		}
		if (candidates.isEmpty()) {
			return new LatePayerFlag(false, 0, windowMonths, minEvents);
		}

		// One include-archived cumulative aggregation per distinct due date (never one-per-contract):
		// ≤ windowMonths queries per distinct payment-terms combination, keyed by (contractId, due)
		// because a contract appears at several due dates.
		Map<ContractDueDate, BigDecimal> paidByKey = new HashMap<>();
		idsByDueDate.forEach((due, ids) ->
			payments().sumPaidThroughDateByContractIdIn(ids, due)
				.forEach(sum -> paidByKey.put(new ContractDueDate(sum.getContractId(), due), sum.getPaidSum())));

		int eventCount = 0;
		for (Candidate candidate : candidates) {
			Contract contract = candidate.contract();
			Instant asOf = candidate.due().plusDays(1).atStartOfDay(zone).toInstant();
			BigDecimal paid = paidByKey.get(new ContractDueDate(contract.getId(), candidate.due()));
			// The rule re-derives the balance as it stood the day after the candidate's due date:
			// every period owed through then vs. every payment dated on/before the due date.
			boolean overdue = rule.evaluate(contract.getMonthlyRent(), contract.getPaymentDayOfMonth(),
				contract.getGraceDays(), contract.getStartDate(), candidate.effectiveEnd(), paid, asOf, zone)
				.overdue();
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

	/** One in-term (contract, period) pair to judge, carrying its precomputed accrual cap + due date. */
	private record Candidate(Contract contract, LocalDate effectiveEnd, LocalDate due) {
	}

	/** Composite map key: a contract appears at several due dates, so contractId alone won't do. */
	private record ContractDueDate(Long contractId, LocalDate due) {
	}
}
