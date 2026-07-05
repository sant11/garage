package com.example.garageops.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractRepository;
import com.example.garageops.garages.Garage;
import com.example.garageops.payments.PaymentRepository.ContractPaidSum;
import com.example.garageops.tenants.Tenant;

/**
 * Verifies {@link OverdueService} joins the single un-windowed total-paid aggregation to the pure
 * cumulative {@link OverdueRule} and projects only overdue contracts to {@link OverdueRow} — mocked
 * repositories, a fixed {@link Clock}, no Spring context. The clock is pinned to Warsaw on
 * 2026-06-20, past the 15th-of-June due date for the fixture (rent 250, day 10, grace 5); the
 * default contract start (2026-06-01) makes June the one-and-only owed period, so the single-period
 * scenarios keep their pre-cumulative amounts.
 *
 * <p>This is the end-to-end service oracle for the plan's partial→full trace: a partial payment
 * leaves the contract overdue for the remainder; the full amount drops it off Dues. It also locks
 * the cumulative-semantics additions at service level: the payment-date-window reproduction
 * regression, multi-month arrears with the FIFO {@code daysOverdue} anchor, and the
 * ended-with-debt visibility rules (decision #3).
 */
class OverdueServiceTests {

	private static final BigDecimal RENT = new BigDecimal("250.00");
	private static final int DAY = 10;
	private static final int GRACE = 5;
	// First owed period = June (due 2026-06-15, on/after the start; May's due date precedes it), so
	// exactly one period is due as of the clock's 2026-06-20 and single-period amounts equal one rent.
	private static final LocalDate START = LocalDate.of(2026, 6, 1);
	private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

	private final Clock clock = Clock.fixed(Instant.parse("2026-06-20T10:00:00Z"), WARSAW);
	private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
	private final ContractRepository contractRepository = mock(ContractRepository.class);
	private final OverdueService service =
		new OverdueService(clock, providerOf(paymentRepository), providerOf(contractRepository));

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		given(provider.getObject()).willReturn(bean);
		return provider;
	}

	@Test
	void reportsAnUnpaidContractAsOverdueWithAmountAndDays() {
		Contract contract = contract(1L, "A-1", "Acme Co");
		given(contractRepository.findNonArchivedForOverdue()).willReturn(List.of(contract));
		// No payment row for the contract → treated as paid-zero by the rule.
		given(paymentRepository.sumPaidTotalByContractIdIn(anyList())).willReturn(List.of());

		List<OverdueRow> dues = service.currentDues();

		assertThat(dues).singleElement().satisfies(row -> {
			assertThat(row.contractId()).isEqualTo(1L);
			assertThat(row.garageLabel()).isEqualTo("A-1");
			assertThat(row.tenantName()).isEqualTo("Acme Co");
			assertThat(row.amountDue()).isEqualByComparingTo(RENT);
			assertThat(row.daysOverdue()).isEqualTo(5L); // 06-15 → 06-20
		});
		// One total-paid aggregation for the whole scan — no date bounds (the whole ledger counts).
		verify(paymentRepository).sumPaidTotalByContractIdIn(List.of(1L));
	}

	@Test
	void aPartialPaymentLeavesTheContractOverdueForTheRemainder() {
		Contract contract = contract(1L, "A-1", "Acme Co");
		ContractPaidSum partial = paidSum(1L, new BigDecimal("100.00"));
		given(contractRepository.findNonArchivedForOverdue()).willReturn(List.of(contract));
		given(paymentRepository.sumPaidTotalByContractIdIn(anyList())).willReturn(List.of(partial));

		List<OverdueRow> dues = service.currentDues();

		assertThat(dues).singleElement().satisfies(row ->
			assertThat(row.amountDue()).isEqualByComparingTo("150.00"));
	}

	@Test
	void aFullyPaidContractDropsOffDues() {
		Contract contract = contract(1L, "A-1", "Acme Co");
		ContractPaidSum full = paidSum(1L, RENT);
		given(contractRepository.findNonArchivedForOverdue()).willReturn(List.of(contract));
		given(paymentRepository.sumPaidTotalByContractIdIn(anyList())).willReturn(List.of(full));

		assertThat(service.currentDues()).isEmpty();
	}

	@Test
	void returnsOnlyTheOverdueContractsFromAMixedPortfolio() {
		Contract paid = contract(1L, "A-1", "Acme Co");
		Contract unpaid = contract(2L, "A-2", "Beta Ltd");
		ContractPaidSum paidInFull = paidSum(1L, RENT);
		given(contractRepository.findNonArchivedForOverdue()).willReturn(List.of(paid, unpaid));
		// Contract 1 paid in full; contract 2 unpaid.
		given(paymentRepository.sumPaidTotalByContractIdIn(anyList())).willReturn(List.of(paidInFull));

		List<OverdueRow> dues = service.currentDues();

		assertThat(dues).singleElement().satisfies(row -> {
			assertThat(row.contractId()).isEqualTo(2L);
			assertThat(row.amountDue()).isEqualByComparingTo(RENT);
		});
	}

	@Test
	void anEmptyPortfolioReturnsEmptyWithoutQueryingPayments() {
		given(contractRepository.findNonArchivedForOverdue()).willReturn(List.of());

		assertThat(service.currentDues()).isEmpty();
		verify(paymentRepository, never()).sumPaidTotalByContractIdIn(any());
	}

	@Test
	void aFutureStartContractIsExcludedEvenWhenUnpaid() {
		// Regression for the F1 future-start bug: a contract whose term begins after the evaluation
		// date owes nothing yet, so it must never surface on Dues — even though it is active
		// (endedOn null) and has no payment. A normal already-started contract is kept alongside it,
		// proving the guard excludes selectively rather than emptying the result.
		Contract started = contract(1L, "A-1", "Acme Co");
		Contract future = contract(2L, "A-2", "Beta Ltd", LocalDate.of(2026, 7, 1)); // after clock's 2026-06-20
		given(contractRepository.findNonArchivedForOverdue()).willReturn(List.of(started, future));
		given(paymentRepository.sumPaidTotalByContractIdIn(anyList())).willReturn(List.of());

		List<OverdueRow> dues = service.currentDues();

		assertThat(dues).singleElement().satisfies(row -> {
			assertThat(row.contractId()).isEqualTo(1L);
			assertThat(row.amountDue()).isEqualByComparingTo(RENT);
		});
		// The future-start contract is filtered out before the aggregation, so only the started
		// contract's id reaches the total-paid query.
		verify(paymentRepository).sumPaidTotalByContractIdIn(List.of(1L));
	}

	@Test
	void aContractStartedAfterThePeriodsDueDateOwesNothingForThatPeriod() {
		// Regression: a contract started 2026-06-18 — after June's due date (06-15) but before the
		// evaluation date (06-20) — is in effect "now", yet June predates its term, so it owes nothing
		// and must not surface on Dues (its first owed period is July, not yet due). The sibling
		// long-started contract stays overdue, proving the guard excludes selectively.
		Contract started = contract(1L, "A-1", "Acme Co");
		Contract justStarted = contract(2L, "A-2", "Beta Ltd", LocalDate.of(2026, 6, 18));
		given(contractRepository.findNonArchivedForOverdue()).willReturn(List.of(started, justStarted));
		given(paymentRepository.sumPaidTotalByContractIdIn(anyList())).willReturn(List.of());

		List<OverdueRow> dues = service.currentDues();

		assertThat(dues).singleElement().satisfies(row -> {
			assertThat(row.contractId()).isEqualTo(1L);
			assertThat(row.amountDue()).isEqualByComparingTo(RENT);
		});
		// The just-started contract is filtered out before the aggregation, so only the long-started
		// contract's id reaches the total-paid query.
		verify(paymentRepository).sumPaidTotalByContractIdIn(List.of(1L));
	}

	@Test
	void duesAsOfHonorsAnExplicitInstantForTheReDerivationSeam() {
		// Started 2026-05-01, unpaid. As of 2026-06-10 only May is fully due → one rent owed; the
		// clock's now (06-20) would owe two (May + June). The single-rent amount proves asOf — not the
		// clock — drives the period counting.
		Contract contract = contract(1L, "A-1", "Acme Co", LocalDate.of(2026, 5, 1));
		given(contractRepository.findNonArchivedForOverdue()).willReturn(List.of(contract));
		given(paymentRepository.sumPaidTotalByContractIdIn(anyList())).willReturn(List.of());

		List<OverdueRow> dues = service.duesAsOf(Instant.parse("2026-06-10T10:00:00Z"));

		assertThat(dues).singleElement().satisfies(row -> {
			assertThat(row.amountDue()).isEqualByComparingTo(RENT);
			assertThat(row.daysOverdue()).isEqualTo(26L); // 05-15 → 06-10
		});
	}

	@Test
	void aRentPaidAfterItsMonthEndsStillClearsTheBalance() {
		// Reproduction regression (change.md): contract 2026-06-01 at 300/month, payment day 1,
		// grace 5; two 300 payments dated 2026-07-04 and 2026-07-05. As of 2026-07-05 only June is
		// fully due (July's due date 07-06 hasn't passed) and the total paid (600) covers it, so the
		// contract must NOT be overdue — the old calendar-window code showed 300 / 29 days because
		// both payments fell in July's window.
		Clock reproClock = Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), WARSAW);
		OverdueService reproService =
			new OverdueService(reproClock, providerOf(paymentRepository), providerOf(contractRepository));
		Contract contract = contract(1L, "A-1", "Acme Co",
			new BigDecimal("300.00"), 1, 5, LocalDate.of(2026, 6, 1), null);
		ContractPaidSum bothPayments = paidSum(1L, new BigDecimal("600.00"));
		given(contractRepository.findNonArchivedForOverdue()).willReturn(List.of(contract));
		given(paymentRepository.sumPaidTotalByContractIdIn(anyList()))
			.willReturn(List.of(bothPayments));

		assertThat(reproService.currentDues()).isEmpty();
	}

	@Test
	void multiMonthArrearsAccumulateWithTheFifoDaysAnchor() {
		// Started 2026-05-01 → May and June both due by 06-20 (500 total); one rent paid. FIFO credit
		// covers May, so the remaining rent is June's and daysOverdue anchors to June's due date —
		// not May's, even though May was the first unpaid-by-window month.
		Contract contract = contract(1L, "A-1", "Acme Co", LocalDate.of(2026, 5, 1));
		ContractPaidSum oneRent = paidSum(1L, RENT);
		given(contractRepository.findNonArchivedForOverdue()).willReturn(List.of(contract));
		given(paymentRepository.sumPaidTotalByContractIdIn(anyList()))
			.willReturn(List.of(oneRent));

		List<OverdueRow> dues = service.currentDues();

		assertThat(dues).singleElement().satisfies(row -> {
			assertThat(row.amountDue()).isEqualByComparingTo(RENT);
			assertThat(row.daysOverdue()).isEqualTo(5L); // June's 06-15 → 06-20
		});
	}

	@Test
	void anEndedContractWithAnUnsettledBalanceStaysListedWithAccrualCapped() {
		// Started 2026-03-01, ended 2026-05-20: March–May accrued (750), June's due date (06-15)
		// falls after endedOn so it does NOT accrue. With 500 paid the row shows the one remaining
		// rent — 250, not 500, which is exactly what asserts the endedOn accrual cap. FIFO covers
		// March and April, so daysOverdue anchors to May's due date.
		Contract ended = contract(1L, "A-1", "Acme Co",
			RENT, DAY, GRACE, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 20));
		ContractPaidSum twoRents = paidSum(1L, new BigDecimal("500.00"));
		given(contractRepository.findNonArchivedForOverdue()).willReturn(List.of(ended));
		given(paymentRepository.sumPaidTotalByContractIdIn(anyList()))
			.willReturn(List.of(twoRents));

		List<OverdueRow> dues = service.currentDues();

		assertThat(dues).singleElement().satisfies(row -> {
			assertThat(row.contractId()).isEqualTo(1L);
			assertThat(row.amountDue()).isEqualByComparingTo(RENT);
			assertThat(row.daysOverdue()).isEqualTo(36L); // May's 05-15 → 06-20
		});
	}

	@Test
	void anEndedContractWithASettledBalanceDropsOffDues() {
		// Same ledger as above with the balance fully paid (3 × 250): the row disappears the moment
		// the frozen debt is covered.
		Contract ended = contract(1L, "A-1", "Acme Co",
			RENT, DAY, GRACE, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 5, 20));
		ContractPaidSum threeRents = paidSum(1L, new BigDecimal("750.00"));
		given(contractRepository.findNonArchivedForOverdue()).willReturn(List.of(ended));
		given(paymentRepository.sumPaidTotalByContractIdIn(anyList()))
			.willReturn(List.of(threeRents));

		assertThat(service.currentDues()).isEmpty();
	}

	@Test
	void aContractEndedBeforeItsFirstDueDateIsExcludedBeforeAggregating() {
		// Started 2026-06-01 and ended 2026-06-10 — before June's due date (06-15) — so nothing ever
		// came due: the pre-filter skips it and the payments table is never queried for it.
		Contract ended = contract(1L, "A-1", "Acme Co",
			RENT, DAY, GRACE, START, LocalDate.of(2026, 6, 10));
		given(contractRepository.findNonArchivedForOverdue()).willReturn(List.of(ended));

		assertThat(service.currentDues()).isEmpty();
		verify(paymentRepository, never()).sumPaidTotalByContractIdIn(any());
	}

	// A mocked Contract so its surrogate id is controllable (a DB-free entity has no id) and the rule
	// inputs are explicit; the service reads only these getters off it. Started at START (one owed
	// period as of the clock) unless a scenario needs its own terms.
	private static Contract contract(long id, String garageLabel, String tenantName) {
		return contract(id, garageLabel, tenantName, START);
	}

	private static Contract contract(long id, String garageLabel, String tenantName, LocalDate startDate) {
		return contract(id, garageLabel, tenantName, RENT, DAY, GRACE, startDate, null);
	}

	private static Contract contract(long id, String garageLabel, String tenantName, BigDecimal rent,
			int paymentDay, int graceDays, LocalDate startDate, LocalDate endedOn) {
		Contract contract = mock(Contract.class);
		given(contract.getId()).willReturn(id);
		given(contract.getMonthlyRent()).willReturn(rent);
		given(contract.getPaymentDayOfMonth()).willReturn(paymentDay);
		given(contract.getGraceDays()).willReturn(graceDays);
		given(contract.getStartDate()).willReturn(startDate);
		given(contract.getEndedOn()).willReturn(endedOn);
		Garage garage = mock(Garage.class);
		given(garage.getLabel()).willReturn(garageLabel);
		given(contract.getGarage()).willReturn(garage);
		Tenant tenant = mock(Tenant.class);
		given(tenant.getName()).willReturn(tenantName);
		given(contract.getTenant()).willReturn(tenant);
		return contract;
	}

	private static ContractPaidSum paidSum(long contractId, BigDecimal sum) {
		ContractPaidSum row = mock(ContractPaidSum.class);
		given(row.getContractId()).willReturn(contractId);
		given(row.getPaidSum()).willReturn(sum);
		return row;
	}
}
