package com.example.garageops.tenants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Verifies {@link TenantService} business logic with mocked repositories — no Spring context, no
 * database, mirroring {@code locations/LocationServiceTests}.
 *
 * <p>The load-bearing oracle is <b>archive-retention</b>: archiving a tenant stamps it and invokes
 * <b>no</b> delete on the repository — the R4 guarantee that archive retains rather than deletes. A
 * tenant has no children, so (unlike {@code LocationServiceTests}) there is no cascade assertion.
 */
class TenantServiceTests {

	private final TenantRepository tenantRepository = mock(TenantRepository.class);
	private final TenantService service = new TenantService(providerOf(tenantRepository));

	// Wrap a mock repository in a mocked ObjectProvider, mirroring the production ObjectProvider
	// wiring exercised by locations/LocationServiceTests.
	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		given(provider.getObject()).willReturn(bean);
		return provider;
	}

	@Test
	void addSavesANewTenantWithTheGivenNameAndContact() {
		given(tenantRepository.save(any(Tenant.class))).willAnswer(call -> call.getArgument(0));

		service.add("Acme Co", "acme@example.com");

		ArgumentCaptor<Tenant> saved = ArgumentCaptor.forClass(Tenant.class);
		verify(tenantRepository).save(saved.capture());
		assertThat(saved.getValue().getName()).isEqualTo("Acme Co");
		assertThat(saved.getValue().getContactInfo()).isEqualTo("acme@example.com");
	}

	@Test
	void editProfileUpdatesBothNameAndContactAndSaves() {
		Tenant tenant = new Tenant("Old", "old@example.com");
		given(tenantRepository.findById(1L)).willReturn(Optional.of(tenant));

		service.editProfile(1L, "New", "new@example.com");

		assertThat(tenant.getName()).isEqualTo("New");
		assertThat(tenant.getContactInfo()).isEqualTo("new@example.com");
		verify(tenantRepository).save(tenant);
	}

	@Test
	void listActiveDelegatesToTheActiveOnlyFinder() {
		Tenant active = new Tenant("Acme Co", null);
		given(tenantRepository.findByArchivedAtIsNullOrderByNameAsc()).willReturn(List.of(active));

		assertThat(service.listActive()).containsExactly(active);
	}

	@Test
	void archiveStampsTheTenantAndDeletesNothing() {
		Tenant tenant = new Tenant("Acme Co", "acme@example.com");
		given(tenantRepository.findById(1L)).willReturn(Optional.of(tenant));

		service.archive(1L);

		// The tenant is stamped — never removed.
		assertThat(tenant.isArchived()).isTrue();
		verify(tenantRepository).save(tenant);

		// R4: no delete reaches the repository.
		verify(tenantRepository, never()).delete(any());
		verify(tenantRepository, never()).deleteById(anyLong());
		verify(tenantRepository, never()).deleteAll();
		verify(tenantRepository, never()).deleteAll(any());
	}
}
