package com.example.garageops.payments;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/**
 * Locks the FR-013 {@link OverdueRule} DB-free and clock-free: every "now" is an explicit
 * {@link Instant} + {@link ZoneId} the test controls. Covers the three decomposed test-plan risks —
 * R1 (overdue false-negative: an unpaid due period is flagged), R2 (boundary/timezone: the
 * due-date edge resolved through the supplied zone, never the system default), R3 (partial-payment
 * summing: under/exact/over) — plus the not-yet-due case (a period whose due date hasn't passed).
 *
 * <p>Fixture: rent 250.00, payment day 10, grace 5 → a month's rent is due on the 15th of that
 * month, and the period is overdue only once the evaluation date is past the 15th.
 */
class OverdueRuleTests {

	private static final BigDecimal RENT = new BigDecimal("250.00");
	private static final int DAY = 10;
	private static final int GRACE = 5;

	private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
	private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

	private final OverdueRule rule = new OverdueRule();

	// --- R1: overdue false-negative — an unpaid, already-due period must be flagged -----------

	@Test
	void flagsAnUnpaidDuePeriodAsOverdue() {
		Instant asOf = Instant.parse("2026-06-20T10:00:00Z"); // Warsaw 2026-06-20, past the 15th

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, BigDecimal.ZERO, asOf, WARSAW);

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

		OverdueResult warsaw = rule.evaluate(RENT, DAY, GRACE, BigDecimal.ZERO, asOf, WARSAW);
		OverdueResult newYork = rule.evaluate(RENT, DAY, GRACE, BigDecimal.ZERO, asOf, NEW_YORK);

		// Warsaw is past the 15th → June is the resolved due period.
		assertThat(warsaw.period()).isEqualTo(YearMonth.of(2026, 6));
		assertThat(warsaw.daysOverdue()).isEqualTo(1L); // 06-15 → 06-16
		// New York is still the 15th → June is not yet due, so May is the latest fully-due period.
		assertThat(newYork.period()).isEqualTo(YearMonth.of(2026, 5));
		// Differing periods from one instant prove the zone is honored, not assumed.
		assertThat(warsaw.period()).isNotEqualTo(newYork.period());
	}

	@Test
	void treatsTheDueDateItselfAsNotYetOverdue() {
		Instant onDueDate = Instant.parse("2026-06-15T09:00:00Z"); // Warsaw 2026-06-15 == due date

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, BigDecimal.ZERO, onDueDate, WARSAW);

		// On the due date June is not yet overdue-evaluable → falls back to the prior fully-due month.
		assertThat(result.period()).isEqualTo(YearMonth.of(2026, 5));
	}

	// --- R3: partial-payment summing — under / exact / over ------------------------------------

	@Test
	void partialPaymentLeavesThePeriodOverdueForTheRemainder() {
		Instant asOf = Instant.parse("2026-06-20T10:00:00Z");

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, new BigDecimal("100.00"), asOf, WARSAW);

		assertThat(result.overdue()).isTrue();
		assertThat(result.amountDue()).isEqualByComparingTo("150.00");
	}

	@Test
	void exactPaymentClearsTheOverdueStatus() {
		Instant asOf = Instant.parse("2026-06-20T10:00:00Z");

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, new BigDecimal("250.00"), asOf, WARSAW);

		assertThat(result.overdue()).isFalse();
		assertThat(result.amountDue()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(result.daysOverdue()).isEqualTo(0L);
	}

	@Test
	void overpaymentClearsTheOverdueStatusAndNeverGoesNegative() {
		Instant asOf = Instant.parse("2026-06-20T10:00:00Z");

		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, new BigDecimal("300.00"), asOf, WARSAW);

		assertThat(result.overdue()).isFalse();
		assertThat(result.amountDue()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	// --- Not-yet-due — current month's due date hasn't passed; prior period is settled ----------

	@Test
	void doesNotFlagAPeriodWhoseDueDateHasNotYetPassed() {
		Instant asOf = Instant.parse("2026-06-10T10:00:00Z"); // Warsaw 2026-06-10, before the 15th

		// The current month (June) isn't due yet, so May is the resolved period; it is fully paid.
		OverdueResult result = rule.evaluate(RENT, DAY, GRACE, new BigDecimal("250.00"), asOf, WARSAW);

		assertThat(result.period()).isEqualTo(YearMonth.of(2026, 5));
		assertThat(result.overdue()).isFalse();
		assertThat(result.amountDue()).isEqualByComparingTo(BigDecimal.ZERO);
	}
}
