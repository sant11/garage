package com.example.garageops.tenants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractRepository;
import com.example.garageops.garages.Garage;
import com.example.garageops.locations.Location;
import com.example.garageops.payments.PaymentService;

import jakarta.persistence.EntityNotFoundException;

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
	private final ContractRepository contractRepository = mock(ContractRepository.class);
	private final PaymentService paymentService = mock(PaymentService.class);
	private final TenantService service = new TenantService(
		providerOf(tenantRepository), providerOf(contractRepository), paymentService);

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
	void findActiveReturnsTheTenantWhenItIsActive() {
		Tenant tenant = new Tenant("Acme Co", "acme@example.com");
		given(tenantRepository.findById(1L)).willReturn(Optional.of(tenant));

		assertThat(service.findActive(1L)).isSameAs(tenant);
	}

	@Test
	void findActiveThrowsWhenTheTenantIsArchived() {
		// An archived tenant must not surface on the profile route — the service throws so the route
		// can 404 rather than render an archived profile.
		Tenant tenant = new Tenant("Acme Co", "acme@example.com");
		tenant.archive();
		given(tenantRepository.findById(1L)).willReturn(Optional.of(tenant));

		assertThatThrownBy(() -> service.findActive(1L))
			.isInstanceOf(EntityNotFoundException.class);
	}

	@Test
	void archiveStampsTheTenantAndCascadeStampsItsContractsAndDeletesNothing() {
		Tenant tenant = new Tenant("Acme Co", "acme@example.com");
		Contract contract = contractFor(tenant);
		given(tenantRepository.findById(1L)).willReturn(Optional.of(tenant));
		given(contractRepository.findByTenantIdAndArchivedAtIsNull(1L)).willReturn(List.of(contract));

		service.archive(1L);

		// The tenant and its contracts are stamped — never removed (FR-021 retention).
		assertThat(tenant.isArchived()).isTrue();
		assertThat(contract.isArchived()).isTrue();
		verify(tenantRepository).save(tenant);
		verify(contractRepository).saveAll(List.of(contract));

		// The cascade reaches the payment side too, so the tenant's payments are retain-stamped.
		verify(paymentService).archivePaymentsForContracts(any());

		// R4: no delete reaches either repository.
		verify(tenantRepository, never()).delete(any());
		verify(tenantRepository, never()).deleteById(anyLong());
		verify(tenantRepository, never()).deleteAll();
		verify(tenantRepository, never()).deleteAll(any());
		verify(contractRepository, never()).delete(any());
		verify(contractRepository, never()).deleteById(anyLong());
		verify(contractRepository, never()).deleteAll();
		verify(contractRepository, never()).deleteAll(any());
	}

	private static Contract contractFor(Tenant tenant) {
		Garage garage = new Garage(new Location("Downtown"), "A-1", new BigDecimal("250.00"));
		return new Contract(tenant, garage, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
			new BigDecimal("250.00"), 1);
	}
}
