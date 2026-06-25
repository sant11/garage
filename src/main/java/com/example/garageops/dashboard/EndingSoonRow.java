package com.example.garageops.dashboard;

import java.time.LocalDate;

/**
 * One row of the dashboard's "ending soon" section (US-01, S-06): an active contract whose planned
 * end falls within the next 30 days, with the columns the owner needs to plan a renewal or turnover.
 * An off-session-safe value type — it carries the garage and tenant labels as already-resolved
 * {@code String}s rather than the lazy entities, so it crosses the view boundary freely under
 * {@code open-in-view=false} (the {@link com.example.garageops.payments.OverdueRow} pattern).
 *
 * @param contractId      the contract ending soon
 * @param garageId        the contract's garage, so the dashboard can drill through to {@code garages/:id}
 * @param garageLabel     the garage's display label
 * @param tenantName      the renting tenant's name
 * @param plannedEndDate  the contract's planned end date
 * @param daysRemaining   whole days from today to {@code plannedEndDate}
 */
public record EndingSoonRow(Long contractId, Long garageId, String garageLabel, String tenantName,
		LocalDate plannedEndDate, long daysRemaining) {
}
