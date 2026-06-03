package com.example.garageops.garages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.example.garageops.locations.Location;
import com.example.garageops.locations.LocationRepository;

/**
 * Verifies {@link GarageService} business logic with mocked repositories — no Spring context, no
 * database, mirroring {@code account/OwnerBootstrapTests}.
 *
 * <p>Covers: {@code add} rejecting an archived parent location, the mark/clear problem round-trip,
 * and {@code edit} updating fields. Mocking stays at the repository boundary only.
 */
class GarageServiceTests {

	private final GarageRepository garageRepository = mock(GarageRepository.class);
	private final LocationRepository locationRepository = mock(LocationRepository.class);
	private final GarageService service =
		new GarageService(providerOf(garageRepository), providerOf(locationRepository));

	// Wrap a mock repository in a mocked ObjectProvider, mirroring the production ObjectProvider
	// wiring exercised by account/OwnerBootstrapTests.
	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		given(provider.getObject()).willReturn(bean);
		return provider;
	}

	@Test
	void addUnderActiveLocationSavesGarageWithLabelAndRent() {
		Location location = new Location("Downtown");
		given(locationRepository.findById(1L)).willReturn(Optional.of(location));
		given(garageRepository.save(any(Garage.class))).willAnswer(call -> call.getArgument(0));

		service.add(1L, "A-1", new BigDecimal("250.00"));

		ArgumentCaptor<Garage> saved = ArgumentCaptor.forClass(Garage.class);
		verify(garageRepository).save(saved.capture());
		Garage garage = saved.getValue();
		assertThat(garage.getLabel()).isEqualTo("A-1");
		assertThat(garage.getMonthlyRent()).isEqualByComparingTo("250.00");
		assertThat(garage.getLocation()).isSameAs(location);
	}

	@Test
	void addRejectsAnArchivedParentLocationAndSavesNothing() {
		Location archived = new Location("Closed");
		archived.archive();
		given(locationRepository.findById(1L)).willReturn(Optional.of(archived));

		assertThatThrownBy(() -> service.add(1L, "A-1", new BigDecimal("250.00")))
			.isInstanceOf(IllegalStateException.class);

		verify(garageRepository, never()).save(any());
	}

	@Test
	void markProblemThenClearProblemRoundTrips() {
		Garage garage = new Garage(new Location("Downtown"), "A-1", new BigDecimal("250.00"));
		given(garageRepository.findById(1L)).willReturn(Optional.of(garage));

		service.markProblem(1L, "Door stuck");
		assertThat(garage.isProblem()).isTrue();
		assertThat(garage.getProblemReason()).isEqualTo("Door stuck");

		service.clearProblem(1L);
		assertThat(garage.isProblem()).isFalse();
		assertThat(garage.getProblemReason()).isNull();
	}

	@Test
	void editUpdatesLabelAndRent() {
		Garage garage = new Garage(new Location("Downtown"), "A-1", new BigDecimal("250.00"));
		given(garageRepository.findById(1L)).willReturn(Optional.of(garage));

		service.edit(1L, "A-2", new BigDecimal("300.00"));

		assertThat(garage.getLabel()).isEqualTo("A-2");
		assertThat(garage.getMonthlyRent()).isEqualByComparingTo("300.00");
		verify(garageRepository).save(garage);
	}

	@Test
	void archiveStampsTheGarageWithoutDeleting() {
		Garage garage = new Garage(new Location("Downtown"), "A-1", new BigDecimal("250.00"));
		given(garageRepository.findById(1L)).willReturn(Optional.of(garage));

		service.archive(1L);

		assertThat(garage.isArchived()).isTrue();
		verify(garageRepository).save(garage);
		verify(garageRepository, never()).delete(any());
	}
}
