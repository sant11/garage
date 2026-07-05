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
 * Verifies {@link OverdueService} joins the batch paid-sum aggregation to the pure {@link OverdueRule}
 * and projects only overdue contracts to {@link OverdueRow} — mocked repositories, a fixed
 * {@link Clock}, no Spring context. The clock is pinned to Warsaw on 2026-06-20, past the 15th-of-June
 * due date for the fixture (rent 250, day 10, grace 5), so June is the resolved period.
 *
 * <p>This is the end-to-end service oracle for the plan's partial→full trace (manual step 3.4): a
 * partial payment leaves the contract overdue for the remainder; the full amount drops it off Dues.
 */
class OverdueServiceTests {

	private static final BigDecimal RENT = new BigDecimal("250.00");
	private static final int DAY = 10;
	private static final int GRACE = 5;
	// Started well before the earliest due date the tests resolve, so the in-effect-on-due-date
	// guard in OverdueService keeps every fixture contract in scope.
	private static final LocalDate START = LocalDate.of(2026, 1, 1);
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
		given(contractRepository.findActiveForOverdue()).willReturn(List.of(contract));
		// No payment row for the contract → treated as paid-zero by the rule.
		given(paymentRepository.sumPaidByContractIdInPeriod(anyList(), any(), any()))
			.willReturn(List.of());

		List<OverdueRow> dues = service.currentDues();

		assertThat(dues).singleElement().satisfies(row -> {
			assertThat(row.contractId()).isEqualTo(1L);
			assertThat(row.garageLabel()).isEqualTo("A-1");
			assertThat(row.tenantName()).isEqualTo("Acme Co");
			assertThat(row.amountDue()).isEqualByComparingTo(RENT);
			assertThat(row.daysOverdue()).isEqualTo(5L); // 06-15 → 06-20
		});
		// One aggregation query bounded to the resolved June period.
		verify(paymentRepository).sumPaidByContractIdInPeriod(
			List.of(1L), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
	}

	@Test
	void aPartialPaymentLeavesTheContractOverdueForTheRemainder() {
		Contract contract = contract(1L, "A-1", "Acme Co");
		ContractPaidSum partial = paidSum(1L, new BigDecimal("100.00"));
		given(contractRepository.findActiveForOverdue()).willReturn(List.of(contract));
		given(paymentRepository.sumPaidByContractIdInPeriod(anyList(), any(), any()))
			.willReturn(List.of(partial));

		List<OverdueRow> dues = service.currentDues();

		assertThat(dues).singleElement().satisfies(row ->
			assertThat(row.amountDue()).isEqualByComparingTo("150.00"));
	}

	@Test
	void aFullyPaidContractDropsOffDues() {
		Contract contract = contract(1L, "A-1", "Acme Co");
		ContractPaidSum full = paidSum(1L, RENT);
		given(contractRepository.findActiveForOverdue()).willReturn(List.of(contract));
		given(paymentRepository.sumPaidByContractIdInPeriod(anyList(), any(), any()))
			.willReturn(List.of(full));

		assertThat(service.currentDues()).isEmpty();
	}

	@Test
	void returnsOnlyTheOverdueContractsFromAMixedPortfolio() {
		Contract paid = contract(1L, "A-1", "Acme Co");
		Contract unpaid = contract(2L, "A-2", "Beta Ltd");
		ContractPaidSum paidInFull = paidSum(1L, RENT);
		given(contractRepository.findActiveForOverdue()).willReturn(List.of(paid, unpaid));
		// Contract 1 paid in full; contract 2 unpaid.
		given(paymentRepository.sumPaidByContractIdInPeriod(anyList(), any(), any()))
			.willReturn(List.of(paidInFull));

		List<OverdueRow> dues = service.currentDues();

		assertThat(dues).singleElement().satisfies(row -> {
			assertThat(row.contractId()).isEqualTo(2L);
			assertThat(row.amountDue()).isEqualByComparingTo(RENT);
		});
	}

	@Test
	void anEmptyPortfolioReturnsEmptyWithoutQueryingPayments() {
		given(contractRepository.findActiveForOverdue()).willReturn(List.of());

		assertThat(service.currentDues()).isEmpty();
		verify(paymentRepository, never()).sumPaidByContractIdInPeriod(any(), any(), any());
	}

	@Test
	void aFutureStartContractIsExcludedEvenWhenUnpaid() {
		// Regression for the F1 future-start bug: a contract whose term begins after the evaluation
		// date owes nothing yet, so it must never surface on Dues — even though it is active
		// (endedOn null) and has no payment. A normal already-started contract is kept alongside it,
		// proving the guard excludes selectively rather than emptying the result.
		Contract started = contract(1L, "A-1", "Acme Co");
		Contract future = contract(2L, "A-2", "Beta Ltd", LocalDate.of(2026, 7, 1)); // after clock's 2026-06-20
		given(contractRepository.findActiveForOverdue()).willReturn(List.of(started, future));
		given(paymentRepository.sumPaidByContractIdInPeriod(anyList(), any(), any()))
			.willReturn(List.of());

		List<OverdueRow> dues = service.currentDues();

		assertThat(dues).singleElement().satisfies(row -> {
			assertThat(row.contractId()).isEqualTo(1L);
			assertThat(row.amountDue()).isEqualByComparingTo(RENT);
		});
		// The future-start contract is filtered out before the period aggregation, so only the
		// started contract's id reaches the paid-sum query.
		verify(paymentRepository).sumPaidByContractIdInPeriod(
			List.of(1L), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
	}

	@Test
	void aContractStartedAfterThePeriodsDueDateOwesNothingForThatPeriod() {
		// Regression: a contract started 2026-06-18 — after June's due date (06-15) but before the
		// evaluation date (06-20) — is in effect "now", yet June predates its term, so it owes nothing
		// and must not surface on Dues (the in-effect-on-due-date guard LatePayerService already
		// applies to past periods). Its first liability is July, once July's due date passes. The
		// sibling long-started contract stays overdue, proving the guard excludes selectively.
		Contract started = contract(1L, "A-1", "Acme Co");
		Contract justStarted = contract(2L, "A-2", "Beta Ltd", LocalDate.of(2026, 6, 18));
		given(contractRepository.findActiveForOverdue()).willReturn(List.of(started, justStarted));
		given(paymentRepository.sumPaidByContractIdInPeriod(anyList(), any(), any()))
			.willReturn(List.of());

		List<OverdueRow> dues = service.currentDues();

		assertThat(dues).singleElement().satisfies(row -> {
			assertThat(row.contractId()).isEqualTo(1L);
			assertThat(row.amountDue()).isEqualByComparingTo(RENT);
		});
		// The just-started contract is filtered out before the aggregation, so only the long-started
		// contract's id reaches the paid-sum query.
		verify(paymentRepository).sumPaidByContractIdInPeriod(
			List.of(1L), LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
	}

	@Test
	void duesAsOfHonorsAnExplicitInstantForTheReDerivationSeam() {
		// Earlier than the June due date: June is not yet due, so May is the resolved period — proving
		// asOf (not the clock's now) drives the period. May unpaid → overdue for May.
		Contract contract = contract(1L, "A-1", "Acme Co");
		given(contractRepository.findActiveForOverdue()).willReturn(List.of(contract));
		given(paymentRepository.sumPaidByContractIdInPeriod(anyList(), any(), any()))
			.willReturn(List.of());

		List<OverdueRow> dues = service.duesAsOf(Instant.parse("2026-06-10T10:00:00Z"));

		assertThat(dues).singleElement().satisfies(row ->
			assertThat(row.amountDue()).isEqualByComparingTo(RENT));
		// The query is bounded to May, confirming the explicit instant resolved the prior period.
		verify(paymentRepository).sumPaidByContractIdInPeriod(
			List.of(1L), LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));
	}

	// A mocked Contract so its surrogate id is controllable (a DB-free entity has no id) and the rule
	// inputs are explicit; the service reads only these getters off it. Started at START (well before
	// any asOf under test).
	private static Contract contract(long id, String garageLabel, String tenantName) {
		return contract(id, garageLabel, tenantName, START);
	}

	private static Contract contract(long id, String garageLabel, String tenantName, LocalDate startDate) {
		Contract contract = mock(Contract.class);
		given(contract.getId()).willReturn(id);
		given(contract.getMonthlyRent()).willReturn(RENT);
		given(contract.getPaymentDayOfMonth()).willReturn(DAY);
		given(contract.getGraceDays()).willReturn(GRACE);
		given(contract.getStartDate()).willReturn(startDate);
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
