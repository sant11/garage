package com.example.garageops.payments;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * The FR-013 overdue rule as a pure, persistence-free unit. Given one contract's terms, its term
 * bounds and the total it has ever paid, it decides whether the contract's cumulative balance is
 * overdue — receiving the paid-sum as an argument rather than reaching for payments itself (the
 * no-parent-collection rule). No Spring, no DB, no clock field: everything that varies is a
 * parameter, so the same rule serves S-06 (call with "now") and S-07 (re-derive past periods by
 * varying {@code asOf}).
 *
 * <p><b>Cumulative balance.</b> A calendar month's rent is due on
 * {@code paymentDayOfMonth + graceDays} days into that month; with the day capped 1–28 that due
 * date always lands in or just after the same month, so the short-month edge is structurally
 * impossible (no clamping). A period only becomes overdue-evaluable once the evaluation date is
 * strictly <em>after</em> its due date — on the due date itself the owner may still pay. The rule
 * counts every owed period from the first one the contract was in effect for (earliest month whose
 * due date falls on/after {@code contractStart}) through the latest fully-due one (capped by
 * {@code effectiveEnd} when the contract has ended), and the contract is overdue iff
 * {@code periodsDue × monthlyRent} exceeds {@code totalPaid}. Payments have no period attribution
 * ({@code Payment} carries only a date), so credit is applied FIFO: {@code totalPaid} covers the
 * oldest periods first, and {@code daysOverdue} anchors to the oldest not-fully-covered period's
 * due date. A skipped month therefore keeps the contract overdue — regardless of later months'
 * payments — until the balance is settled.
 *
 * <p><b>Why an explicit zone.</b> An {@link Instant} alone cannot be resolved to a calendar month —
 * it needs a zone. The zone is a parameter (not the JVM default) so the boundary is deterministic
 * and testable across zones (test-plan R2); production always supplies the fixed-zone application
 * {@link java.time.Clock}'s {@code instant()} and {@code getZone()}.
 */
public class OverdueRule {

	/**
	 * Evaluate the overdue status of one contract's cumulative balance as of {@code asOf},
	 * interpreted in {@code zone}.
	 *
	 * @param monthlyRent       the contract's monthly rent
	 * @param paymentDayOfMonth the day-of-month rent is due (1–28)
	 * @param graceDays         days of grace added to the payment day before a period is overdue
	 * @param contractStart     the contract's start date; the first owed period is the earliest month
	 *                          whose due date falls on or after this date
	 * @param effectiveEnd      the date accrual stops ({@code endedOn}); {@code null} = unbounded —
	 *                          periods whose due date falls after it are never owed
	 * @param totalPaid         the total the contract has ever paid ({@code null} treated as zero)
	 * @param asOf              the instant to evaluate "now" at
	 * @param zone              the zone {@code asOf} is resolved to a calendar date in
	 */
	public OverdueResult evaluate(BigDecimal monthlyRent, int paymentDayOfMonth, int graceDays,
			LocalDate contractStart, LocalDate effectiveEnd,
			BigDecimal totalPaid, Instant asOf, ZoneId zone) {
		LocalDate asOfDate = asOf.atZone(zone).toLocalDate();
		YearMonth first = firstOwedPeriod(contractStart, paymentDayOfMonth, graceDays);
		YearMonth last = latestFullyDuePeriod(asOfDate, paymentDayOfMonth, graceDays);
		if (effectiveEnd != null) {
			while (dueDate(last, paymentDayOfMonth, graceDays).isAfter(effectiveEnd) && !last.isBefore(first)) {
				last = last.minusMonths(1);
			}
		}

		long periodsDue = last.isBefore(first) ? 0 : ChronoUnit.MONTHS.between(first, last) + 1;
		BigDecimal paid = totalPaid == null ? BigDecimal.ZERO : totalPaid;
		BigDecimal amountDue = monthlyRent.multiply(BigDecimal.valueOf(periodsDue))
				.subtract(paid).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
		boolean overdue = amountDue.signum() > 0;

		if (!overdue) {
			return new OverdueResult(periodsDue == 0 ? null : last, false, amountDue, 0L);
		}
		// FIFO: totalPaid covers whole periods oldest-first; the anchor is the oldest uncovered one.
		long covered = Math.min(periodsDue, paid.divideToIntegralValue(monthlyRent).longValue());
		YearMonth anchor = first.plusMonths(covered);
		long daysOverdue = ChronoUnit.DAYS.between(dueDate(anchor, paymentDayOfMonth, graceDays), asOfDate);
		return new OverdueResult(anchor, true, amountDue, daysOverdue);
	}

	/**
	 * The "latest fully-due period" for one contract as of {@code asOf}, interpreted in {@code zone} —
	 * the upper bound of the periods {@link #evaluate} counts. Exposed so a caller (the
	 * {@code OverdueService}) can resolve the period <em>before</em> it knows the paid-sum, while the
	 * period logic stays in this one pure unit.
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

	// The earliest month whose due date falls on/after contractStart — NOT YearMonth.from(start):
	// with a late payment day + grace a period's due date spills into the next month, so a contract
	// can owe the month before its start month; conversely a contract started after its own month's
	// due date first owes the next month. Same predicate as the services' in-effect-on-due-date guard.
	private YearMonth firstOwedPeriod(LocalDate contractStart, int paymentDayOfMonth, int graceDays) {
		YearMonth month = YearMonth.from(contractStart);
		while (dueDate(month, paymentDayOfMonth, graceDays).isBefore(contractStart)) {
			month = month.plusMonths(1);
		}
		while (!dueDate(month.minusMonths(1), paymentDayOfMonth, graceDays).isBefore(contractStart)) {
			month = month.minusMonths(1);
		}
		return month;
	}

	/**
	 * The due date of {@code period} under the given terms — {@code paymentDayOfMonth} days into the
	 * month plus {@code graceDays}. Public because the services guard on it: a contract owes a period
	 * only if it was in effect on that period's due date ({@code OverdueService} for the live period,
	 * {@code LatePayerService} for past ones).
	 */
	public LocalDate dueDate(YearMonth period, int paymentDayOfMonth, int graceDays) {
		return period.atDay(paymentDayOfMonth).plusDays(graceDays);
	}
}
