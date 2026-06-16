package com.example.garageops.payments;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single application {@link Clock}, fixed to {@code Europe/Warsaw} (test-plan R2). All
 * "now"-dependent overdue arithmetic resolves the current instant and zone through this bean rather
 * than {@code Instant.now()} / {@code LocalDate.now()} / the JVM default zone, so classification is
 * deterministic and CI-zone-independent.
 *
 * <p>The injectable clock was deliberately deferred to S-05 by the contracts slice. Tests inject a
 * {@link Clock#fixed} instead of this bean, pinning both the instant and the zone.
 */
@Configuration
public class ClockConfig {

	/** The zone every overdue calculation is evaluated in. */
	public static final ZoneId ZONE = ZoneId.of("Europe/Warsaw");

	@Bean
	Clock clock() {
		return Clock.system(ZONE);
	}
}
