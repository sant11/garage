package com.example.garageops.locations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.example.garageops.garages.Garage;
import com.example.garageops.garages.GarageRepository;

/**
 * Verifies {@link LocationService} business logic with mocked repositories — no Spring context, no
 * database, mirroring {@code account/OwnerBootstrapTests}.
 *
 * <p>The load-bearing oracle is the <b>cascade-stamp</b>: archiving a location with N active garages
 * stamps the location <em>and</em> each active garage, and invokes <b>no</b> delete on either
 * repository — the R4-adjacent guarantee that archive retains rather than deletes.
 */
class LocationServiceTests {

	private final LocationRepository locationRepository = mock(LocationRepository.class);
	private final GarageRepository garageRepository = mock(GarageRepository.class);
	private final LocationService service =
		new LocationService(providerOf(locationRepository), providerOf(garageRepository));

	// Wrap a mock repository in a mocked ObjectProvider, mirroring the production ObjectProvider
	// wiring exercised by account/OwnerBootstrapTests.
	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		given(provider.getObject()).willReturn(bean);
		return provider;
	}

	@Test
	void addSavesANewLocationWithTheGivenName() {
		given(locationRepository.save(any(Location.class))).willAnswer(call -> call.getArgument(0));

		service.add("Downtown");

		ArgumentCaptor<Location> saved = ArgumentCaptor.forClass(Location.class);
		verify(locationRepository).save(saved.capture());
		assertThat(saved.getValue().getName()).isEqualTo("Downtown");
	}

	@Test
	void renameUpdatesTheLocationName() {
		Location location = new Location("Old");
		given(locationRepository.findById(1L)).willReturn(Optional.of(location));

		service.rename(1L, "New");

		assertThat(location.getName()).isEqualTo("New");
		verify(locationRepository).save(location);
	}

	@Test
	void listActiveDelegatesToTheActiveOnlyFinder() {
		Location active = new Location("Downtown");
		given(locationRepository.findByArchivedAtIsNullOrderByNameAsc()).willReturn(List.of(active));

		assertThat(service.listActive()).containsExactly(active);
	}

	@Test
	void archiveCascadeStampsActiveGaragesAndDeletesNothing() {
		Location location = new Location("Downtown");
		Garage g1 = new Garage(location, "A-1", new BigDecimal("250.00"));
		Garage g2 = new Garage(location, "A-2", new BigDecimal("300.00"));
		given(locationRepository.findById(1L)).willReturn(Optional.of(location));
		given(garageRepository.findByLocationIdAndArchivedAtIsNull(1L)).willReturn(List.of(g1, g2));

		service.archive(1L);

		// Location and every active garage are stamped — never removed.
		assertThat(location.isArchived()).isTrue();
		assertThat(g1.isArchived()).isTrue();
		assertThat(g2.isArchived()).isTrue();
		verify(locationRepository).save(location);
		verify(garageRepository).saveAll(List.of(g1, g2));

		// R4: no delete reaches either repository.
		verify(locationRepository, never()).delete(any());
		verify(locationRepository, never()).deleteById(anyLong());
		verify(garageRepository, never()).delete(any());
		verify(garageRepository, never()).deleteById(anyLong());
		verify(garageRepository, never()).deleteAll(any());
	}
}
