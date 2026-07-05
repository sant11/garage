package com.example.garageops.payments;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractRepository;

/**
 * Derives the live overdue picture for the portfolio (FR-013), joining the batch total-paid
 * aggregation to the pure cumulative {@link OverdueRule}. {@link #currentDues()} answers "what is
 * overdue right now" by resolving the instant through the injected fixed-zone {@link Clock};
 * {@link #duesAsOf} keeps {@code asOf} an explicit parameter so S-07 can later re-derive past
 * periods through the same path.
 *
 * <p><b>One aggregation — literally one query.</b> The cumulative balance needs each contract's
 * total ever paid, so a single un-windowed {@code SUM ... GROUP BY contract} covers the whole scan
 * regardless of contract count (no per-period grouping, no N+1). The summed totals feed the rule
 * per contract with its term bounds; a contract with no payments is simply absent from the sum and
 * treated as paid-zero.
 *
 * <p><b>Ended-but-unsettled contracts stay listed.</b> The scan covers every non-archived contract
 * — including ended ones — because a debt survives the contract ending: accrual is capped at
 * {@code endedOn} (passed to the rule as {@code effectiveEnd}), so the balance freezes and the row
 * drops off the moment payments cover it. Archiving remains the write-off gesture that removes the
 * row (FR-021).
 *
 * <p>Rows are projected to {@link OverdueRow} (labels resolved here, inside the fetch) because the
 * Dues view renders off-session under {@code open-in-view=false}. The {@link OverdueRule} is a pure,
 * stateless value, so it is held as a plain field rather than injected.
 *
 * <p>{@link ContractRepository} is injected as an {@link ObjectProvider} resolved per call, mirroring
 * the sibling services — it keeps the bean constructible in the DB-free test context. The
 * {@link Clock} is a real bean ({@code ClockConfig}) present in every context.
 */
@Service
public class OverdueService {

	private final OverdueRule rule = new OverdueRule();
	private final Clock clock;
	private final ObjectProvider<PaymentRepository> paymentRepository;
	private final ObjectProvider<ContractRepository> contractRepository;

	public OverdueService(
			Clock clock,
			ObjectProvider<PaymentRepository> paymentRepository,
			ObjectProvider<ContractRepository> contractRepository) {
		this.clock = clock;
		this.paymentRepository = paymentRepository;
		this.contractRepository = contractRepository;
	}

	/** @return the currently-overdue rows for the whole non-archived portfolio, as of the clock's now. */
	public List<OverdueRow> currentDues() {
		return duesAsOf(clock.instant());
	}

	/**
	 * @return the overdue rows for every non-archived contract as of {@code asOf} (the S-07
	 *         re-derivation seam). Batch-sums each contract's total payments, runs the pure
	 *         cumulative rule against its term bounds, and keeps only the overdue contracts as
	 *         off-session-safe {@link OverdueRow}s.
	 */
	public List<OverdueRow> duesAsOf(Instant asOf) {
		List<Contract> inScope = contracts().findNonArchivedForOverdue().stream()
			.filter(c -> couldOweAnything(c, asOf))
			.toList();
		if (inScope.isEmpty()) {
			return List.of();
		}
		List<Long> ids = inScope.stream().map(Contract::getId).toList();
		Map<Long, BigDecimal> paidByContract = new HashMap<>();
		payments().sumPaidTotalByContractIdIn(ids)
			.forEach(sum -> paidByContract.put(sum.getContractId(), sum.getPaidSum()));

		List<OverdueRow> dues = new ArrayList<>();
		for (Contract contract : inScope) {
			OverdueResult result = rule.evaluate(contract.getMonthlyRent(),
				contract.getPaymentDayOfMonth(), contract.getGraceDays(),
				contract.getStartDate(), contract.getEndedOn(),
				paidByContract.get(contract.getId()), asOf, clock.getZone());
			if (result.overdue()) {
				dues.add(new OverdueRow(contract.getId(), contract.getGarage().getId(),
					contract.getGarage().getLabel(), contract.getTenant().getName(),
					result.amountDue(), result.daysOverdue()));
			}
		}
		return dues;
	}

	// Cheap pre-filter — the rule's zero-owed-periods case already makes these contracts not-overdue,
	// but skipping them here spares the aggregation a row per contract that cannot owe anything: one
	// that starts after the latest fully-due period's due date (future-start or started-after-due),
	// or one that ended before its first owed period's due date (nothing ever came due).
	private boolean couldOweAnything(Contract contract, Instant asOf) {
		int day = contract.getPaymentDayOfMonth();
		int grace = contract.getGraceDays();
		YearMonth latest = rule.latestFullyDuePeriod(day, grace, asOf, clock.getZone());
		if (contract.getStartDate().isAfter(rule.dueDate(latest, day, grace))) {
			return false;
		}
		if (contract.getEndedOn() != null) {
			LocalDate firstDue = rule.dueDate(
				rule.firstOwedPeriod(contract.getStartDate(), day, grace), day, grace);
			return !contract.getEndedOn().isBefore(firstDue);
		}
		return true;
	}

	private PaymentRepository payments() {
		return paymentRepository.getObject();
	}

	private ContractRepository contracts() {
		return contractRepository.getObject();
	}
}
