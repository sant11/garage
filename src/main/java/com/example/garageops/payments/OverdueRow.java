package com.example.garageops.payments;

import java.math.BigDecimal;

/**
 * One row of the Dues view (US-01, {@code prd.md:54}): a currently-overdue garage with the columns
 * the owner needs to chase payment. An off-session-safe value type — it carries the garage label and
 * tenant name as already-resolved {@code String}s rather than the lazy entities, so it crosses the
 * view boundary freely under {@code open-in-view=false}.
 *
 * @param contractId  the overdue contract, so the view can drill through to its garage
 * @param garageLabel the garage's display label
 * @param tenantName  the renting tenant's name
 * @param amountDue   {@code monthlyRent − paidInPeriod} for the resolved period, never negative
 * @param daysOverdue whole days the resolved period is past its due date
 */
public record OverdueRow(Long contractId, String garageLabel, String tenantName,
		BigDecimal amountDue, long daysOverdue) {
}
