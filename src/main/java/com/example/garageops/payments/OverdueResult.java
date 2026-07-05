package com.example.garageops.payments;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * The outcome of evaluating one contract's cumulative balance as of a given instant (see
 * {@link OverdueRule}). Off-session-safe value type carrying only primitives, so it crosses the
 * view boundary freely ({@code open-in-view=false}).
 *
 * @param period       when overdue, the FIFO anchor — the oldest owed period not fully covered by
 *                     {@code totalPaid}; when not overdue, the latest owed period ({@code null}
 *                     when no period is owed yet)
 * @param overdue      {@code true} when the cumulative balance is positive as-of the evaluation
 *                     instant
 * @param amountDue    {@code periodsDue × monthlyRent − totalPaid}, floored at zero (never
 *                     negative); may exceed one month's rent when several periods are in arrears
 * @param daysOverdue  whole days between the anchor period's due date and the evaluation date;
 *                     {@code 0} when not overdue
 */
public record OverdueResult(YearMonth period, boolean overdue, BigDecimal amountDue, long daysOverdue) {
}
