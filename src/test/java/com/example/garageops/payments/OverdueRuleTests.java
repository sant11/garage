package com.example.garageops.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/**
 * Locks the FR-013 cumulative-balance {@link OverdueRule} DB-free and clock-free: every "now" is an
 * explicit {@link Instant} + {@link ZoneId} the test controls. Covers the three decomposed test-plan
 * risks — R1 (overdue false-negative: an unpaid due period is flagged), R2 (boundary/timezone: the
 * due-date edge resolved through the supplied zone, never the system default), R3 (partial-payment
 * summing: under/exact/over) — plus the cumulative-specific cases: the reproduction regression
 * (late-paid month credited), multi-month FIFO anchoring, prepayment credit, the zero-owed-periods
 * case, the {@code effectiveEnd} accrual cap, and the day-28 due-date spill that pins the
 * first-owed-period predicate.
 *
 * <p>Fixture: rent 250.00, payment day 10, grace 5 → a month's rent is due on the 15th of that
 * month, and the period is overdue only once the evaluation date is past the 15th. Contracts start
 * 2026-06-01 (single owed period June) or 2026-01-01 (multi-month arrears Jan…) unless the case
 * needs otherwise.
 */
class OverdueRuleTests {

	private static final BigDecimal RENT = new BigDecimal("250.00");
	private static final int DAY = 10;
	private static final int GRACE = 5;

	private static final LocalDate START_JUNE = LocalDate.of(2026, 6, 1);
	private static final LocalDate START_JAN = LocalDate.of(2026, 1, 1);

	private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	private final OverdueRule rule = new OverdueRule();

	// --- R1: overdue false-negative — an unpaid, already-due period must be flagged -----------

	@Test
	void flagsAnUnpaidDuePeriodAsOverdue() {
		Instant asOf = Instant.parse("2026-06-20T10:00:00Z"); // Warsaw 2026-06-20, past the 15th

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, START_JUNE, null,
				BigDecimal.ZERO, asOf, WARSAW);

		assertThat(result.period()).isEqualTo(YearMonth.of(2026, 6));
		assertThat(result.overdue()).isTrue();
		assertThat(result.amountDue()).isEqualByComparingTo(RENT);
		assertThat(result.daysOverdue()).isEqualTo(5L); // 06-15 → 06-20
	}

	// --- R2: boundary / timezone — same instant, two zones, resolved through the supplied zone --

	@Test
	void resolvesTheDueBoundaryThroughTheSuppliedZoneNotTheSystemDefault() {
		// 23:30Z on the 15th: already the 16th in Warsaw (UTC+2 in June) but still the 15th in NY.
		Instant asOf = Instant.parse("2026-06-15T23:30:00Z");

		OverdueResult warsaw = rule.evaluate(RENT, DAY, GRACE, START_JUNE, null,
				BigDecimal.ZERO, asOf, WARSAW);
		OverdueResult newYork = rule.evaluate(RENT, DAY, GRACE, START_JUNE, null,
				BigDecimal.ZERO, asOf, NEW_YORK);

		// Warsaw is past the 15th → June is due and unpaid.
		assertThat(warsaw.overdue()).isTrue();
		assertThat(warsaw.period()).isEqualTo(YearMonth.of(2026, 6));
		assertThat(warsaw.daysOverdue()).isEqualTo(1L); // 06-15 → 06-16
		// New York is still the 15th → June isn't fully due yet, and the contract owes no earlier
		// period, so nothing is owed at all. Differing verdicts from one instant prove the zone is
		// honored, not assumed.
		assertThat(newYork.overdue()).isFalse();
		assertThat(newYork.period()).isNull();
	}

	@Test
	void treatsTheDueDateItselfAsNotYetOverdue() {
		Instant onDueDate = Instant.parse("2026-06-15T09:00:00Z"); // Warsaw 2026-06-15 == due date

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, START_JUNE, null,
				BigDecimal.ZERO, onDueDate, WARSAW);

		// On the due date June is not yet overdue-evaluable, and no earlier period is owed.
		assertThat(result.overdue()).isFalse();
		assertThat(result.period()).isNull();
	}

	// --- R3: partial-payment summing — under / exact / over ------------------------------------

	@Test
	void partialPaymentLeavesThePeriodOverdueForTheRemainder() {
		Instant asOf = Instant.parse("2026-06-20T10:00:00Z");

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, START_JUNE, null,
				new BigDecimal("100.00"), asOf, WARSAW);

		assertThat(result.overdue()).isTrue();
		assertThat(result.amountDue()).isEqualByComparingTo("150.00");
		// A partial payment covers no whole period, so the anchor stays on June's due date.
		assertThat(result.daysOverdue()).isEqualTo(5L);
	}

	@Test
	void exactPaymentClearsTheOverdueStatus() {
		Instant asOf = Instant.parse("2026-06-20T10:00:00Z");

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, START_JUNE, null,
				new BigDecimal("250.00"), asOf, WARSAW);

		assertThat(result.overdue()).isFalse();
		assertThat(result.amountDue()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(result.daysOverdue()).isEqualTo(0L);
		assertThat(result.period()).isEqualTo(YearMonth.of(2026, 6)); // latest owed period
	}

	@Test
	void overpaymentClearsTheOverdueStatusAndNeverGoesNegative() {
		Instant asOf = Instant.parse("2026-06-20T10:00:00Z");

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, START_JUNE, null,
				new BigDecimal("300.00"), asOf, WARSAW);

		assertThat(result.overdue()).isFalse();
		assertThat(result.amountDue()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	// --- Reproduction regression — a rent paid after its month ended still clears the balance ---

	@Test
	void aRentPaidInTheFollowingMonthStillClearsTheBalance() {
		// The motivating bug: contract from 2026-06-01, rent 300, day 1, grace 5; 600 paid in total
		// (both payments dated early July). As of 2026-07-05 only June is fully due (July's due date
		// is 07-06), so 600 paid ≥ 300 due → nothing overdue; the calendar-window semantics wrongly
		// reported June as unpaid because both payment dates fell in July's window.
		BigDecimal rent = new BigDecimal("300.00");
		Instant asOf = Instant.parse("2026-07-05T10:00:00Z"); // Warsaw 2026-07-05

		OverdueResult result = rule.evaluate(rent, 1, 5, LocalDate.of(2026, 6, 1), null,
				new BigDecimal("600.00"), asOf, WARSAW);

		assertThat(result.overdue()).isFalse();
		assertThat(result.amountDue()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	// --- Multi-month arrears — FIFO coverage anchors daysOverdue to the oldest uncovered period --

	@Test
	void multiMonthArrearsAccumulateAndAnchorToTheOldestUncoveredPeriod() {
		// Jan–Mar owed (3 periods), one rent paid → 500 outstanding. FIFO: the payment covers
		// January, so the anchor is February's due date (02-15), 33 days before 2026-03-20.
		Instant asOf = Instant.parse("2026-03-20T10:00:00Z"); // Warsaw 2026-03-20

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, START_JAN, null,
				new BigDecimal("250.00"), asOf, WARSAW);

		assertThat(result.overdue()).isTrue();
		assertThat(result.amountDue()).isEqualByComparingTo("500.00");
		assertThat(result.period()).isEqualTo(YearMonth.of(2026, 2));
		assertThat(result.daysOverdue()).isEqualTo(33L); // 02-15 → 03-20
	}

	@Test
	void aFurtherPartialPaymentDoesNotMoveTheAnchorUntilAFullPeriodIsCovered() {
		Instant asOf = Instant.parse("2026-03-20T10:00:00Z");

		// 350 paid = one full period (January) + 100 toward February → anchor stays on February.
		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, START_JAN, null,
				new BigDecimal("350.00"), asOf, WARSAW);

		assertThat(result.overdue()).isTrue();
		assertThat(result.amountDue()).isEqualByComparingTo("400.00");
		assertThat(result.period()).isEqualTo(YearMonth.of(2026, 2));
		assertThat(result.daysOverdue()).isEqualTo(33L);
	}

	// --- Prepayment — credit beyond the periods due so far never reads as overdue ----------------

	@Test
	void prepaymentBeyondTheDuePeriodsIsCreditNotOverdue() {
		// Jan–Mar owed (750); 1000 paid → 250 of credit toward April. Not overdue, nothing due.
		Instant asOf = Instant.parse("2026-03-20T10:00:00Z");

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, START_JAN, null,
				new BigDecimal("1000.00"), asOf, WARSAW);

		assertThat(result.overdue()).isFalse();
		assertThat(result.amountDue()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(result.period()).isEqualTo(YearMonth.of(2026, 3)); // latest owed period
	}

	// --- Zero owed periods — a contract started after the only elapsed due date owes nothing ----

	@Test
	void aContractStartedAfterTheOnlyElapsedDueDateOwesNothing() {
		// Started 2026-06-18: June's due date (06-15) predates the term, and July isn't due yet as
		// of 2026-06-20, so no period is owed — not overdue even with zero paid.
		Instant asOf = Instant.parse("2026-06-20T10:00:00Z");

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, LocalDate.of(2026, 6, 18), null,
				BigDecimal.ZERO, asOf, WARSAW);

		assertThat(result.overdue()).isFalse();
		assertThat(result.amountDue()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(result.period()).isNull();
		assertThat(result.daysOverdue()).isEqualTo(0L);
	}

	// --- effectiveEnd cap — periods whose due date falls after the end don't accrue --------------

	@Test
	void periodsDueAfterTheEffectiveEndDoNotAccrue() {
		// Ended 2026-04-30: Jan–Apr owed (May's and June's due dates fall after the end). As of
		// 2026-06-20 with 750 paid the frozen balance is one rent, anchored to April (the first
		// three payments cover Jan–Mar FIFO).
		Instant asOf = Instant.parse("2026-06-20T10:00:00Z");
		LocalDate endedOn = LocalDate.of(2026, 4, 30);

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, START_JAN, endedOn,
				new BigDecimal("750.00"), asOf, WARSAW);

		assertThat(result.overdue()).isTrue();
		assertThat(result.amountDue()).isEqualByComparingTo("250.00");
		assertThat(result.period()).isEqualTo(YearMonth.of(2026, 4));
		assertThat(result.daysOverdue()).isEqualTo(66L); // 04-15 → 06-20
	}

	@Test
	void settlingTheAccruedPeriodsOfAnEndedContractClearsIt() {
		Instant asOf = Instant.parse("2026-06-20T10:00:00Z");
		LocalDate endedOn = LocalDate.of(2026, 4, 30);

		// Four periods accrued (Jan–Apr) and four rents paid → settled, despite two more calendar
		// months having elapsed since the end.
		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, START_JAN, endedOn,
				new BigDecimal("1000.00"), asOf, WARSAW);

		assertThat(result.overdue()).isFalse();
		assertThat(result.amountDue()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	// --- Day-28 spill — the first owed period is defined by its due date, not the start month ----

	@Test
	void aContractOwesThePreviousMonthWhenThatMonthsDueDateSpillsPastItsStart() {
		// Payment day 28 + grace 5 → May's due date is 2026-06-02. A contract starting 2026-06-01
		// was in effect on that due date, so its first owed period is May — one month before its
		// start month. As of 2026-06-03 (past 06-02) May is fully due and unpaid.
		Instant asOf = Instant.parse("2026-06-03T10:00:00Z"); // Warsaw 2026-06-03

		OverdueResult result = rule.evaluate(RENT, 28, 5, START_JUNE, null,
				BigDecimal.ZERO, asOf, WARSAW);

		assertThat(result.overdue()).isTrue();
		assertThat(result.period()).isEqualTo(YearMonth.of(2026, 5));
		assertThat(result.amountDue()).isEqualByComparingTo(RENT);
		assertThat(result.daysOverdue()).isEqualTo(1L); // 06-02 → 06-03
	}
}
