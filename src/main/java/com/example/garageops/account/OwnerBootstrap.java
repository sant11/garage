package com.example.garageops.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Provisions the single owner on startup (S-01 Phase 3), replacing self-registration. When the
 * {@code users} table is empty it seeds one {@link OwnerAccount} from the {@code OWNER_*}-backed
 * properties; otherwise it no-ops, so redeploys never duplicate or overwrite the owner.
 *
 * <p>The configured {@code password-hash} is already a BCrypt <em>hash</em> and is stored verbatim
 * — never re-encoded.
 *
 * <p>The repository is injected as an {@link ObjectProvider}: this runner only acts when a
 * repository bean exists. In the DB-free test context (JPA autoconfig excluded by convention) the
 * provider is empty, so the runner does nothing and the test context never requires a database.
 *
 * <p>Under the {@code production} profile the runner refuses to seed with the built-in development
 * password hash: {@code OWNER_PASSWORD_HASH} must be supplied, or startup aborts — so a forgotten
 * env var fails fast instead of silently provisioning a publicly-known credential.
 */
@Component
public class OwnerBootstrap implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(OwnerBootstrap.class);

	/** Documented local-dev fallback; forbidden as a seed under the {@code production} profile. */
	static final String DEFAULT_DEV_PASSWORD_HASH =
			"$2a$10$QMGr6Q3SaPmUEkg6/ukov.oRuLjMXe502Lj5WHIgrWAi/dGcBh26a";

	private final ObjectProvider<OwnerAccountRepository> repository;
	private final Environment environment;
	private final String username;
	private final String email;
	private final String passwordHash;

	public OwnerBootstrap(
			ObjectProvider<OwnerAccountRepository> repository,
			Environment environment,
			@Value("${garageops.owner.username:owner}") String username,
			@Value("${garageops.owner.email:owner@example.com}") String email,
			@Value("${garageops.owner.password-hash:" + DEFAULT_DEV_PASSWORD_HASH + "}") String passwordHash) {
		this.repository = repository;
		this.environment = environment;
		this.username = username;
		this.email = email;
		this.passwordHash = passwordHash;
	}

	@Override
	public void run(ApplicationArguments args) {
		OwnerAccountRepository repo = repository.getIfAvailable();
		if (repo == null) {
			log.debug("Owner bootstrap: no repository available (DB-free context) — skipping seed.");
			return;
		}
		if (repo.count() > 0) {
			log.info("Owner bootstrap: an owner already exists — skipping seed (idempotent).");
			return;
		}
		if (DEFAULT_DEV_PASSWORD_HASH.equals(passwordHash) && environment.matchesProfiles("production")) {
			throw new IllegalStateException(
					"Refusing to seed the owner with the built-in development password hash under the "
					+ "'production' profile. Set the OWNER_PASSWORD_HASH environment variable to a BCrypt hash.");
		}
		repo.save(new OwnerAccount(username, email, passwordHash));
		log.info("Owner bootstrap: seeded owner '{}' from configured credentials.", username);
	}
}
