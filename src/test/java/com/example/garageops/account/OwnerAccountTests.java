package com.example.garageops.account;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pure-POJO test of the {@link OwnerAccount} entity (mirrors {@code ArchivableEntityTests}; no
 * database or Spring context). Locks the load-bearing invariant for owner authentication: the
 * BCrypt {@code passwordHash} is stored <b>verbatim</b> — the entity never encodes or re-encodes it,
 * so the hash that authenticates against {@code PasswordEncoder} is exactly what the bootstrap seeds.
 */
class OwnerAccountTests {

	private static final String USERNAME = "owner";
	private static final String EMAIL = "owner@example.com";
	// A representative BCrypt hash; the entity must surface it unchanged.
	private static final String PASSWORD_HASH = "$2a$10$QMGr6Q3SaPmUEkg6/ukov.oRuLjMXe502Lj5WHIgrWAi/dGcBh26a";

	@Test
	void constructorExposesProvisionedCredentials() {
		OwnerAccount owner = new OwnerAccount(USERNAME, EMAIL, PASSWORD_HASH);

		assertThat(owner.getUsername()).isEqualTo(USERNAME);
		assertThat(owner.getEmail()).isEqualTo(EMAIL);
		assertThat(owner.getPasswordHash()).isEqualTo(PASSWORD_HASH);
	}

	@Test
	void passwordHashIsStoredVerbatim() {
		OwnerAccount owner = new OwnerAccount(USERNAME, EMAIL, PASSWORD_HASH);

		// No re-encoding: the stored value is identical, character for character, to the input.
		assertThat(owner.getPasswordHash()).isSameAs(PASSWORD_HASH);
		assertThat(owner.getPasswordHash()).startsWith("$2a$");
	}
}
