package com.example.garageops.payments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * The FR-013 overdue rule as a pure, persistence-free unit. Given one contract's terms and the
 * sum already paid into the relevant period, it decides which period is currently due and whether
 * that period is overdue — receiving the paid-sum as an argument rather than reaching for payments
 * itself (the no-parent-collection rule). No Spring, no DB, no clock field: everything that varies
 * is a parameter, so the same rule serves S-06 (call with "now") and S-07 (re-derive past periods
 * by varying {@code asOf}).
 *
 * <p><b>Latest fully-due period.</b> A calendar month's rent is due on
 * {@code paymentDayOfMonth + graceDays} days into that month; with the day capped 1–28 that due
 * date always lands in or just after the same month, so the short-month edge is structurally
 * impossible (no clamping). A period only becomes overdue-evaluable once the evaluation date is
 * strictly <em>after</em> its due date — on the due date itself the owner may still pay. The rule
 * resolves the most recent month whose due date has passed and reasons about that single period;
 * an earlier unpaid month therefore stays the resolved period (and stays overdue) until it is paid.
 *
 * <p><b>Why an explicit zone.</b> An {@link Instant} alone cannot be resolved to a calendar month —
 * it needs a zone. The zone is a parameter (not the JVM default) so the boundary is deterministic
 * and testable across zones (test-plan R2); production always supplies the fixed-zone application
 * {@link java.time.Clock}'s {@code instant()} and {@code getZone()}.
 */
public class OverdueRule {

	/**
	 * Evaluate the overdue status of one contract as of {@code asOf}, interpreted in {@code zone}.
	 *
	 * @param monthlyRent       the contract's monthly rent
	 * @param paymentDayOfMonth the day-of-month rent is due (1–28)
	 * @param graceDays         days of grace added to the payment day before a period is overdue
	 * @param paidInPeriod      the sum already paid into the resolved period ({@code null} treated as zero)
	 * @param asOf              the instant to evaluate "now" at
	 * @param zone              the zone {@code asOf} is resolved to a calendar date in
	 */
	public OverdueResult evaluate(BigDecimal monthlyRent, int paymentDayOfMonth, int graceDays,
			BigDecimal paidInPeriod, Instant asOf, ZoneId zone) {
		LocalDate asOfDate = asOf.atZone(zone).toLocalDate();
		YearMonth period = latestFullyDuePeriod(asOfDate, paymentDayOfMonth, graceDays);

		BigDecimal paid = paidInPeriod == null ? BigDecimal.ZERO : paidInPeriod;
		BigDecimal amountDue = monthlyRent.subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
		boolean overdue = amountDue.signum() > 0;
		long daysOverdue = overdue
				? ChronoUnit.DAYS.between(dueDate(period, paymentDayOfMonth, graceDays), asOfDate)
				: 0L;

		return new OverdueResult(period, overdue, amountDue, daysOverdue);
	}

	/**
	 * The "latest fully-due period" for one contract as of {@code asOf}, interpreted in {@code zone} —
	 * the same period {@link #evaluate} reasons about. Exposed so a caller (the Phase 3
	 * {@code OverdueService}) can resolve the period <em>before</em> it knows the paid-sum, in order to
	 * bound the aggregation query, while the period logic stays in this one pure unit.
	 */
	public YearMonth latestFullyDuePeriod(int paymentDayOfMonth, int graceDays, Instant asOf, ZoneId zone) {
		return latestFullyDuePeriod(asOf.atZone(zone).toLocalDate(), paymentDayOfMonth, graceDays);
	}

	/** The most recent month whose due date is strictly before {@code asOfDate}. */
	private YearMonth latestFullyDuePeriod(LocalDate asOfDate, int paymentDayOfMonth, int graceDays) {
		YearMonth month = YearMonth.from(asOfDate);
		while (!asOfDate.isAfter(dueDate(month, paymentDayOfMonth, graceDays))) {
			month = month.minusMonths(1);
		}
		return month;
	}

	private LocalDate dueDate(YearMonth period, int paymentDayOfMonth, int graceDays) {
		return period.atDay(paymentDayOfMonth).plusDays(graceDays);
	}
}
