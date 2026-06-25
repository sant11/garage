package com.example.garageops.payments;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The two FR-020 tuning knobs for the frequent-late-payer flag (roadmap S-07), externalized because
 * PRD Open Q1 calls both numbers provisional defaults to tune without a logic change.
 *
 * <p>Constructor-bound: absent keys fall back to the canonical-constructor defaults (2 / 6), so an
 * existing deploy needs no env change. Registered via {@code @EnableConfigurationProperties} on
 * {@code ClockConfig} — the codebase has no {@code @ConfigurationPropertiesScan}.
 *
 * @param minEvents    overdue events in the window at or above which a tenant is flagged (default 2)
 * @param windowMonths how many most-recent fully-due periods to look back over (default 6)
 */
@ConfigurationProperties(prefix = "garageops.late-payer")
public record LatePayerProperties(int minEvents, int windowMonths) {

	public LatePayerProperties {
		if (minEvents <= 0) {
			minEvents = 2;
		}
		if (windowMonths <= 0) {
			windowMonths = 6;
		}
	}
}
