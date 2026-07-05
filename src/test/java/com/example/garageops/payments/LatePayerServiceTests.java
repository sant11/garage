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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractRepository;
import com.example.garageops.payments.PaymentRepository.ContractPaidSum;

/**
 * Verifies {@link LatePayerService} re-runs the cumulative {@link OverdueRule} at each in-term
 * period's due date and counts the (contract, period) events — periods whose due date passed with
 * the balance still negative the next day — against the configured threshold. Mocked repositories,
 * a fixed {@link Clock}, no Spring context (mirrors {@code OverdueServiceTests}).
 *
 * <p>The clock is pinned to Warsaw on 2026-06-20, past the 15th-of-June due date for the fixture
 * (rent 250, day 10, grace 5), so the latest fully-due period is June 2026 and the default 6-month
 * window is Jan…Jun 2026. Payment history is modeled as a per-contract ledger (date → amount); the
 * stub answers the cumulative-through-due-date aggregation by summing ledger entries on/before the
 * queried date, so a month "paid on time" means its rent lands on its own due date and a skipped
 * month leaves the running balance short at every later due date (the cascade).
 */
class LatePayerServiceTests {

	private static final BigDecimal RENT = new BigDecimal("250.00");
	private static final int DAY = 10;
	private static final int GRACE = 5;
	private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");

	// A contract in term across the whole window: starts well before Jan 2026, ends well after June.
	private static final LocalDate FULL_START = LocalDate.of(2025, 1, 1);
	private static final LocalDate FULL_END = LocalDate.of(2027, 1, 1);
	// The first owed period of a FULL_START contract (due 2025-01-15 falls on/after the start date).
	private static final YearMonth JAN_2025 = YearMonth.of(2025, 1);

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
		// Every rent through May 2026 paid on its due date; June unpaid → exactly one event (minEvents - 1).
		givenLedgers(Map.of(1L, paidOnTime(JAN_2025, MAY)));

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
		// Paid on time through April; May and June never paid → the balance is short at both due
		// dates → exactly two events.
		givenLedgers(Map.of(1L, paidOnTime(JAN_2025, APR)));

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isTrue();
		assertThat(flag.eventCount()).isEqualTo(2);
	}

	@Test
	void twoContractsOverdueInTheSameMonthCountAsTwoEvents() {
		Contract a = contract(1L, FULL_START, FULL_END, null);
		Contract b = contract(2L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(a, b));
		// Both paid on time through May, both short in June only.
		givenLedgers(Map.of(
			1L, paidOnTime(JAN_2025, MAY),
			2L, paidOnTime(JAN_2025, MAY)));

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isTrue();
		assertThat(flag.eventCount()).isEqualTo(2); // per (contract, period), not per period
	}

	@Test
	void endedAndArchivedContractsBothContribute() {
		// One ended contract (endedOn within the window) and one archived-but-still-returned contract;
		// each has exactly one late period, so together they reach the threshold. History must not
		// vanish when a contract ends/archives (FR-021).
		Contract ended = contract(1L, FULL_START, FULL_END, LocalDate.of(2026, 6, 30));
		Contract archived = contract(2L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(ended, archived));
		// ended: June never paid. archived: May's rent paid 5 days late (before June's due date), so
		// May is its only late period — a late-but-eventually-paid event.
		NavigableMap<LocalDate, BigDecimal> archivedLedger = paidOnTime(JAN_2025, JUN, MAY);
		archivedLedger.merge(LocalDate.of(2026, 5, 20), RENT, BigDecimal::add);
		givenLedgers(Map.of(
			1L, paidOnTime(JAN_2025, MAY),
			2L, archivedLedger));

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isTrue();
		assertThat(flag.eventCount()).isEqualTo(2);
		// History reconstruction uses the include-archived aggregation, never the live (excludes-archived) one.
		verify(paymentRepository, never()).sumPaidTotalByContractIdIn(any());
	}

	@Test
	void anArchivedButFullyPaidHistoryHasNoEvents() {
		// Guards the include-archived query: an archived contract whose rents were all genuinely paid
		// on time must not be flagged, even though archiving cascade-stamps its payments.
		Contract archived = contract(1L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(archived));
		givenLedgers(Map.of(1L, paidOnTime(JAN_2025, JUN))); // every owed rent on its due date

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isFalse();
		assertThat(flag.eventCount()).isZero();
	}

	@Test
	void periodsOutsideTheContractTermAreNotCounted() {
		// In term only Feb–May (started 2026-02-10, ended 2026-05-31): Jan is pre-start and June is
		// post-end, so neither can be an event even though nothing was ever paid.
		Contract c = contract(1L, LocalDate.of(2026, 2, 10), FULL_END, LocalDate.of(2026, 5, 31));
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(c));
		givenLedgers(Map.of()); // nothing paid

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.eventCount()).isEqualTo(4); // Feb, Mar, Apr, May — not Jan, not June
		// Out-of-term due dates are never even queried.
		verify(paymentRepository, never()).sumPaidThroughDateByContractIdIn(any(), eq(dueDate(JAN)));
		verify(paymentRepository, never()).sumPaidThroughDateByContractIdIn(any(), eq(dueDate(JUN)));
	}

	@Test
	void aThinHistoryEvaluatesOnlyInTermPeriods() {
		// Two months of history (started 2026-05-10): only May and June are in term. May paid on its
		// due date, June unpaid → a single event, below threshold — not flagged on thin history alone.
		Contract c = contract(1L, LocalDate.of(2026, 5, 10), FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(c));
		givenLedgers(Map.of(1L, paidOnTime(MAY, MAY)));

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isFalse();
		assertThat(flag.eventCount()).isEqualTo(1);
	}

	@Test
	void theWindowIncludesTheOldestPeriodButNothingBeyondIt() {
		Contract c = contract(1L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(c));
		// Late only in Jan 2026 (the 6th and oldest in-window period): its rent lands 5 days late but
		// before Feb's due date, so Jan is the only period whose due date passes with a short balance.
		NavigableMap<LocalDate, BigDecimal> ledger = paidOnTime(JAN_2025, JUN, JAN);
		ledger.merge(LocalDate.of(2026, 1, 20), RENT, BigDecimal::add);
		givenLedgers(Map.of(1L, ledger));

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.eventCount()).isEqualTo(1); // Jan is inside the window
		verify(paymentRepository).sumPaidThroughDateByContractIdIn(anyList(), eq(dueDate(JAN)));
		// Dec 2025 is one month beyond the window and its due date is never queried.
		verify(paymentRepository, never()).sumPaidThroughDateByContractIdIn(any(), eq(dueDate(DEC_2025)));
	}

	@Test
	void aLateButEventuallyPaidMonthCountsAsOneEvent() {
		// March's rent arrives 5 days late (after due(Mar), before due(Apr)); everything else on time.
		// The balance was short the day after March's due date → exactly one event, and clearing the
		// arrears doesn't erase it — but one late month alone stays below the threshold.
		Contract c = contract(1L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(c));
		NavigableMap<LocalDate, BigDecimal> ledger = paidOnTime(JAN_2025, JUN, MAR);
		ledger.merge(LocalDate.of(2026, 3, 20), RENT, BigDecimal::add);
		givenLedgers(Map.of(1L, ledger));

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isFalse();
		assertThat(flag.eventCount()).isEqualTo(1);
	}

	@Test
	void persistentArrearsCascadeIntoAnEventPerPeriod() {
		// February skipped and never caught up: every later rent arrives on time but credits the
		// oldest debt first (FIFO), so the balance is short at every subsequent due date — an event
		// per in-window period from February on. Persistent arrears must trip the flag.
		Contract c = contract(1L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(c));
		givenLedgers(Map.of(1L, paidOnTime(JAN_2025, JUN, FEB)));

		LatePayerFlag flag = service.evaluate(7L);

		assertThat(flag.flagged()).isTrue();
		assertThat(flag.eventCount()).isEqualTo(5); // Feb, Mar, Apr, May, Jun
	}

	@Test
	void theConfiguredThresholdIsRespected() {
		Contract c = contract(1L, FULL_START, FULL_END, null);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(7L)).willReturn(List.of(c));
		givenLedgers(Map.of(1L, paidOnTime(JAN_2025, APR))); // May and June unpaid → 2 events

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
		verify(paymentRepository, never()).sumPaidThroughDateByContractIdIn(any(), any());
	}

	// Stub the cumulative include-archived aggregation from per-contract payment ledgers
	// (date → amount): a queried (ids, through) call answers, per contract, the sum of its ledger
	// entries dated on/before `through`; a contract with no such entries is omitted from the result
	// (treated as paid-zero by the rule), mirroring SUM ... GROUP BY.
	private void givenLedgers(Map<Long, NavigableMap<LocalDate, BigDecimal>> ledgers) {
		given(paymentRepository.sumPaidThroughDateByContractIdIn(anyList(), any()))
			.willAnswer(invocation -> {
				List<Long> ids = invocation.getArgument(0);
				LocalDate through = invocation.getArgument(1);
				List<ContractPaidSum> sums = new ArrayList<>();
				for (Long id : ids) {
					Map<LocalDate, BigDecimal> paid = ledgers
						.getOrDefault(id, Collections.emptyNavigableMap()).headMap(through, true);
					if (!paid.isEmpty()) {
						sums.add(paidSum(id, paid.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)));
					}
				}
				return sums;
			});
	}

	// A ledger paying RENT on each period's due date from `from` through `to`, except the `skipped`
	// months — the on-time baseline tests then perturb with late entries via merge().
	private static NavigableMap<LocalDate, BigDecimal> paidOnTime(YearMonth from, YearMonth to, YearMonth... skipped) {
		Set<YearMonth> skip = Set.of(skipped);
		NavigableMap<LocalDate, BigDecimal> ledger = new TreeMap<>();
		for (YearMonth month = from; !month.isAfter(to); month = month.plusMonths(1)) {
			if (!skip.contains(month)) {
				ledger.merge(dueDate(month), RENT, BigDecimal::add);
			}
		}
		return ledger;
	}

	private static LocalDate dueDate(YearMonth period) {
		return period.atDay(DAY).plusDays(GRACE);
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
