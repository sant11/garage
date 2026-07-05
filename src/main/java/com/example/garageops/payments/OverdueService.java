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
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractRepository;

/**
 * Derives the live overdue picture for the portfolio (FR-013), joining the batch per-period paid-sum
 * aggregation to the pure {@link OverdueRule}. {@link #currentDues()} answers "what is overdue right
 * now" by resolving the instant through the injected fixed-zone {@link Clock}; {@link #duesAsOf} keeps
 * {@code asOf} an explicit parameter so S-07 can later re-derive past periods through the same path.
 *
 * <p><b>One aggregation, not N.</b> Each active contract resolves its own "latest fully-due period"
 * (it depends on that contract's payment day + grace), so the contracts are grouped by resolved
 * period and one {@code SUM ... GROUP BY} runs per <em>distinct period</em> — at most a couple, never
 * one-per-contract (the N+1 the test-plan calls out). The summed amounts feed the rule per contract;
 * a contract with no payment in its period is simply absent from the sum and treated as paid-zero.
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

	/** @return the currently-overdue rows for the whole active portfolio, as of the clock's now. */
	public List<OverdueRow> currentDues() {
		return duesAsOf(clock.instant());
	}

	/**
	 * @return the overdue rows for every active contract as of {@code asOf} (the S-07 re-derivation
	 *         seam). Resolves each contract's due period, batch-sums the period's payments, runs the
	 *         pure rule, and keeps only the overdue contracts as off-session-safe {@link OverdueRow}s.
	 */
	public List<OverdueRow> duesAsOf(Instant asOf) {
		List<Contract> active = contracts().findActiveForOverdue().stream()
			.filter(c -> inEffectOnDueDate(c, asOf))
			.toList();
		if (active.isEmpty()) {
			return List.of();
		}
		Map<Long, BigDecimal> paidByContract = sumPaidPerPeriod(active, asOf);

		List<OverdueRow> dues = new ArrayList<>();
		for (Contract contract : active) {
			OverdueResult result = rule.evaluate(contract.getMonthlyRent(), contract.getPaymentDayOfMonth(),
				contract.getGraceDays(), paidByContract.get(contract.getId()), asOf, clock.getZone());
			if (result.overdue()) {
				dues.add(new OverdueRow(contract.getId(), contract.getGarage().getId(),
					contract.getGarage().getLabel(), contract.getTenant().getName(),
					result.amountDue(), result.daysOverdue()));
			}
		}
		return dues;
	}

	// A contract owes its resolved period only if it was in effect on that period's due date — the
	// same guard LatePayerService applies to past periods. A contract that starts after the due date
	// (including one that starts in the future) owed nothing for the period, so it must not surface
	// as overdue merely because the period predates its term.
	private boolean inEffectOnDueDate(Contract contract, Instant asOf) {
		int day = contract.getPaymentDayOfMonth();
		int grace = contract.getGraceDays();
		YearMonth period = rule.latestFullyDuePeriod(day, grace, asOf, clock.getZone());
		LocalDate due = rule.dueDate(period, day, grace);
		return !contract.getStartDate().isAfter(due);
	}

	// Group the contracts by their resolved due period and run one aggregation per distinct period,
	// so the whole scan costs a handful of queries regardless of contract count. Returns contractId →
	// paid-sum; contracts with no qualifying payment are absent (the rule treats null as zero).
	private Map<Long, BigDecimal> sumPaidPerPeriod(List<Contract> contracts, Instant asOf) {
		ZoneId zone = clock.getZone();
		Map<YearMonth, List<Contract>> byPeriod = contracts.stream().collect(Collectors.groupingBy(
			c -> rule.latestFullyDuePeriod(c.getPaymentDayOfMonth(), c.getGraceDays(), asOf, zone)));

		Map<Long, BigDecimal> paidByContract = new HashMap<>();
		byPeriod.forEach((period, group) -> {
			List<Long> ids = group.stream().map(Contract::getId).toList();
			payments().sumPaidByContractIdInPeriod(ids, period.atDay(1), period.atEndOfMonth())
				.forEach(sum -> paidByContract.put(sum.getContractId(), sum.getPaidSum()));
		});
		return paidByContract;
	}

	private PaymentRepository payments() {
		return paymentRepository.getObject();
	}

	private ContractRepository contracts() {
		return contractRepository.getObject();
	}
}
