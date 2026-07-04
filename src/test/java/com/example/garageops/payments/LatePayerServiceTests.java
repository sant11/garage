package com.example.garageops.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractRepository;
import com.example.garageops.payments.PaymentRepository.ContractPaidSum;

/**
 * Verifies {@link LatePayerService} re-runs the pure {@link OverdueRule} over each contract's recent
 * fully-due periods and counts overdue (contract, period) events against the configured threshold —
 * mocked repositories, a fixed {@link Clock}, no Spring context (mirrors {@code OverdueServiceTests}).
 *
 * <p>The clock is pinned to Warsaw on 2026-06-20, past the 15th-of-June due date for the fixture
 * (rent 250, day 10, grace 5), so the latest fully-due period is June 2026 and the default 6-month
 * window is Jan…Jun 2026. A period counts as overdue iff its contract is in term on the due date and
 * was not fully paid that month; the payment stub below models "fully paid" months per contract.
 */
class LatePayerServiceTests {

	private static final BigDecimal RENT = new BigDecimal("250.00");
	private static final int DAY = 10;
	private static final int GRACE = 5;
	private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

	// A contract in term across the whole window: starts well before Jan 2026, ends well after June.
	private static final LocalDate FULL_START = LocalDate.of(2025, 1, 1);
	private static final LocalDate FULL_END = LocalDate.of(2027, 1, 1);

	private static final YearMonth JUN = YearMonth.of(2026, 6);
	private static final YearMonth MAY = YearMonth.of(2026, 5);
	private static final YearMonth APR = YearMonth.of(2026, 4);
	private static final YearMonth MAR = YearMonth.of(2026, 3);
	private static final YearMonth FEB = YearMonth.of(2026, 2);
	private static final YearMonth JAN = YearMonth.of(2026, 1);
	private static final YearMonth DEC_2025 = YearMonth.of(2025, 12);

	private final Clock clock = Clock.fixed(Instant.parse("2026-06-20T10:00:00Z"), WARSAW);
	private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
	private final ContractRepository contractRepository = mock(ContractRepository.class);
	private final LatePayerService service = serviceWith(new LatePayerProperties(2, 6));

	private LatePayerService serviceWith(LatePayerProperties properties) {
		return new LatePayerService(
			clock, providerOf(contractRepository), providerOf(paymentRepository), properties);
	}

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		given(provider.getObject()).willReturn(bean);
		return provider;
	}

	@Test
	void belowThresholdIsNotFlagged() {
		Contract c = contract(1L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(c));
		// Only June is unpaid → exactly one overdue event (minEvents - 1).
		givenFullyPaid(Map.of(1L, Set.of(MAY, APR, MAR, FEB, JAN)));

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isFalse();
		assertThat(flag.eventCount()).isEqualTo(1);
		assertThat(flag.windowMonths()).isEqualTo(6);
		assertThat(flag.minEvents()).isEqualTo(2);
	}

	@Test
	void atThresholdIsFlagged() {
		Contract c = contract(1L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(c));
		// June and May unpaid → exactly two overdue events.
		givenFullyPaid(Map.of(1L, Set.of(APR, MAR, FEB, JAN)));

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isTrue();
		assertThat(flag.eventCount()).isEqualTo(2);
	}

	@Test
	void twoContractsOverdueInTheSameMonthCountAsTwoEvents() {
		Contract a = contract(1L, FULL_START, FULL_END, null);
		Contract b = contract(2L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(a, b));
		// Both unpaid in June only; every other month paid for both.
		givenFullyPaid(Map.of(
			1L, Set.of(MAY, APR, MAR, FEB, JAN),
			2L, Set.of(MAY, APR, MAR, FEB, JAN)));

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isTrue();
		assertThat(flag.eventCount()).isEqualTo(2); // per (contract, period), not per period
	}

	@Test
	void endedAndArchivedContractsBothContribute() {
		// One ended contract (endedOn within the window) and one archived-but-still-returned contract;
		// each is overdue in a different month, so together they reach the threshold. History must not
		// vanish when a contract ends/archives (FR-021).
		Contract ended = contract(1L, FULL_START, FULL_END, LocalDate.of(2026, 6, 30));
		Contract archived = contract(2L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(ended, archived));
		// ended unpaid in June; archived unpaid in May.
		givenFullyPaid(Map.of(
			1L, Set.of(MAY, APR, MAR, FEB, JAN),
			2L, Set.of(JUN, APR, MAR, FEB, JAN)));

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isTrue();
		assertThat(flag.eventCount()).isEqualTo(2);
		// History reconstruction uses the include-archived aggregation, never the live (excludes-archived) one.
		verify(paymentRepository, never()).sumPaidByContractIdInPeriod(any(), any(), any());
	}

	@Test
	void anArchivedButFullyPaidPeriodIsNotAnEvent() {
		// Guards the include-archived query: an archived contract whose periods were genuinely paid
		// must not be flagged, even though archiving cascade-stamps its payments.
		Contract archived = contract(1L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(archived));
		givenFullyPaid(Map.of(1L, Set.of(JUN, MAY, APR, MAR, FEB, JAN))); // every in-window period paid

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isFalse();
		assertThat(flag.eventCount()).isZero();
	}

	@Test
	void periodsOutsideTheContractTermAreNotCounted() {
		// In term only Feb–May (started 2026-02-10, ended 2026-05-31): Jan is pre-start and June is
		// post-end, so neither can be an overdue event even though every month is unpaid.
		Contract c = contract(1L, LocalDate.of(2026, 2, 10), FULL_END, LocalDate.of(2026, 5, 31));
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(c));
		givenFullyPaid(Map.of()); // nothing paid

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.eventCount()).isEqualTo(4); // Feb, Mar, Apr, May — not Jan, not June
		// Out-of-term months are never even queried.
		verify(paymentRepository, never())
			.sumPaidByContractIdInPeriodIncludingArchived(any(), eq(JAN.atDay(1)), any());
		verify(paymentRepository, never())
			.sumPaidByContractIdInPeriodIncludingArchived(any(), eq(JUN.atDay(1)), any());
	}

	@Test
	void aThinHistoryEvaluatesOnlyInTermPeriods() {
		// Two months of history (started 2026-05-10): only May and June are in term. May paid, June
		// unpaid → a single event, below threshold — not flagged on thin history alone.
		Contract c = contract(1L, LocalDate.of(2026, 5, 10), FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(c));
		givenFullyPaid(Map.of(1L, Set.of(MAY)));

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isFalse();
		assertThat(flag.eventCount()).isEqualTo(1);
	}

	@Test
	void theWindowIncludesTheOldestPeriodButNothingBeyondIt() {
		Contract c = contract(1L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(c));
		// Unpaid only in Jan 2026 (the 6th and oldest in-window period); everything else paid.
		givenFullyPaid(Map.of(1L, Set.of(JUN, MAY, APR, MAR, FEB)));

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.eventCount()).isEqualTo(1); // Jan is inside the window
		verify(paymentRepository)
			.sumPaidByContractIdInPeriodIncludingArchived(anyList(), eq(JAN.atDay(1)), eq(JAN.atEndOfMonth()));
		// Dec 2025 is one month beyond the window and is never queried.
		verify(paymentRepository, never())
			.sumPaidByContractIdInPeriodIncludingArchived(any(), eq(DEC_2025.atDay(1)), any());
	}

	@Test
	void theConfiguredThresholdIsRespected() {
		Contract c = contract(1L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(c));
		givenFullyPaid(Map.of(1L, Set.of(APR, MAR, FEB, JAN))); // June and May unpaid → 2 events

		LatePayerFlag flag = serviceWith(new LatePayerProperties(3, 6)).evaluate(7L);

		assertThat(flag.eventCount()).isEqualTo(2);
		assertThat(flag.flagged()).isFalse(); // 2 < 3
		assertThat(flag.minEvents()).isEqualTo(3);
	}

	@Test
	void aTenantWithNoContractsIsNotFlaggedAndQueriesNoPayments() {
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of());

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isFalse();
		assertThat(flag.eventCount()).isZero();
		verify(paymentRepository, never())
			.sumPaidByContractIdInPeriodIncludingArchived(any(), any(), any());
	}

	// Stub the include-archived aggregation from a map of contractId → set of fully-paid periods. For a
	// queried (period, ids) call, a contract present for that period returns a RENT-sized paid sum (not
	// overdue); any contract absent for that period is omitted from the result (treated as paid-zero by
	// the rule → overdue).
	private void givenFullyPaid(Map<Long, Set<YearMonth>> paidPeriodsByContract) {
		given(paymentRepository.sumPaidByContractIdInPeriodIncludingArchived(anyList(), any(), any()))
			.willAnswer(invocation -> {
				List<Long> ids = invocation.getArgument(0);
				YearMonth period = YearMonth.from((LocalDate) invocation.getArgument(1));
				List<ContractPaidSum> sums = new ArrayList<>();
				for (Long id : ids) {
					if (paidPeriodsByContract.getOrDefault(id, Set.of()).contains(period)) {
						sums.add(paidSum(id, RENT));
					}
				}
				return sums;
			});
	}

	// A mocked Contract so its surrogate id is controllable (a DB-free entity has no id) and the rule
	// inputs are explicit; the service reads only these getters.
	private static Contract contract(long id, LocalDate start, LocalDate plannedEnd, LocalDate endedOn) {
		Contract contract = mock(Contract.class);
		given(contract.getId()).willReturn(id);
		given(contract.getMonthlyRent()).willReturn(RENT);
		given(contract.getPaymentDayOfMonth()).willReturn(DAY);
		given(contract.getGraceDays()).willReturn(GRACE);
		given(contract.getStartDate()).willReturn(start);
		given(contract.getPlannedEndDate()).willReturn(plannedEnd);
		given(contract.getEndedOn()).willReturn(endedOn);
		return contract;
	}

	private static ContractPaidSum paidSum(long contractId, BigDecimal sum) {
		ContractPaidSum row = mock(ContractPaidSum.class);
		given(row.getContractId()).willReturn(contractId);
		given(row.getPaidSum()).willReturn(sum);
		return row;
	}
}
