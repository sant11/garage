package com.example.garageops.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Verifies the {@link OwnerBootstrap} idempotency contract with a mocked repository — no Spring
 * context, no database. The runner must seed exactly one owner from the configured credentials when
 * the {@code users} table is empty, and must save nothing when an owner already exists, so redeploys
 * never duplicate or overwrite the single login identity.
 *
 * <p>The repository is supplied via a mocked {@link ObjectProvider} to mirror the production wiring;
 * the empty-provider (DB-free) path is exercised by {@code SecurityGatingTests} loading the real
 * context.
 */
class OwnerBootstrapTests {

	private static final String USERNAME = "owner";
	private static final String EMAIL = "owner@example.com";
	private static final String PASSWORD_HASH = "$2a$10$QMGr6Q3SaPmUEkg6/ukov.oRuLjMXe502Lj5WHIgrWAi/dGcBh26a";

	@SuppressWarnings("unchecked")
	private final ObjectProvider<OwnerAccountRepository> provider = mock(ObjectProvider.class);
	private final OwnerAccountRepository repository = mock(OwnerAccountRepository.class);

	private OwnerBootstrap newBootstrap() {
		given(provider.getIfAvailable()).willReturn(repository);
		return new OwnerBootstrap(provider, USERNAME, EMAIL, PASSWORD_HASH);
	}

	@Test
	void seedsOneOwnerFromConfiguredCredentialsWhenTableIsEmpty() {
		given(repository.count()).willReturn(0L);

		newBootstrap().run(null);

		ArgumentCaptor<OwnerAccount> saved = ArgumentCaptor.forClass(OwnerAccount.class);
		verify(repository).save(saved.capture());
		OwnerAccount owner = saved.getValue();
		assertThat(owner.getUsername()).isEqualTo(USERNAME);
		assertThat(owner.getEmail()).isEqualTo(EMAIL);
		// Hash stored verbatim — the bootstrap never re-encodes the configured BCrypt value.
		assertThat(owner.getPasswordHash()).isEqualTo(PASSWORD_HASH);
	}

	@Test
	void noOpsWhenAnOwnerAlreadyExists() {
		given(repository.count()).willReturn(1L);

		newBootstrap().run(null);

		verify(repository, never()).save(any());
	}
}
