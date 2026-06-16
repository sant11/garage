package com.example.garageops.payments;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * The outcome of evaluating one contract's overdue status as of a given instant (see
 * {@link OverdueRule}). Off-session-safe value type carrying only primitives, so it crosses the
 * view boundary freely ({@code open-in-view=false}).
 *
 * @param period       the resolved "latest fully-due period" the verdict is about
 * @param overdue      {@code true} when that period is not fully paid as-of the evaluation instant
 * @param amountDue    {@code monthlyRent − paidInPeriod}, floored at zero (never negative)
 * @param daysOverdue  whole days between the period's due date and the evaluation date; {@code 0}
 *                     when not overdue
 */
public record OverdueResult(YearMonth period, boolean overdue, BigDecimal amountDue, long daysOverdue) {
}
