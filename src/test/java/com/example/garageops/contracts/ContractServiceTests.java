package com.example.garageops.contracts;

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
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import com.example.garageops.garages.Garage;
import com.example.garageops.garages.GarageRepository;
import com.example.garageops.locations.Location;
import com.example.garageops.tenants.Tenant;
import com.example.garageops.tenants.TenantRepository;

/**
 * Verifies {@link ContractService} business logic with mocked repositories — no Spring context, no
 * database, mirroring {@code GarageServiceTests} / {@code LocationServiceTests}.
 *
 * <p>The load-bearing oracle is the <b>overlap rejection</b> (R6 root): a create whose date window
 * intersects an existing non-ended contract on the same garage is rejected and saves nothing, which
 * is what makes "≤ 1 active contract per garage" — and therefore vacant/rented — trustworthy. The
 * single-day-touch boundary is covered explicitly. Ending a contract stamps {@code endedOn} and
 * never deletes (R4).
 */
class ContractServiceTests {

	private static final LocalDate START = LocalDate.of(2026, 1, 1);
	private static final LocalDate PLANNED_END = LocalDate.of(2026, 12, 31);
	private static final BigDecimal RENT = new BigDecimal("250.00");

	private final ContractRepository contractRepository = mock(ContractRepository.class);
	private final TenantRepository tenantRepository = mock(TenantRepository.class);
	private final GarageRepository garageRepository = mock(GarageRepository.class);
	private final ContractService service = new ContractService(
		providerOf(contractRepository), providerOf(tenantRepository), providerOf(garageRepository));

	// Wrap a mock repository in a mocked ObjectProvider, mirroring the production ObjectProvider
	// wiring exercised by the sibling service tests.
	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		given(provider.getObject()).willReturn(bean);
		return provider;
	}

	@Test
	void createSavesAContractWithTheGivenFields() {
		Tenant tenant = new Tenant("Acme Co", null);
		Garage garage = new Garage(new Location("Downtown"), "A-1", RENT);
		given(tenantRepository.findById(1L)).willReturn(Optional.of(tenant));
		given(garageRepository.findById(2L)).willReturn(Optional.of(garage));
		given(contractRepository.findByGarageIdAndEndedOnIsNull(2L)).willReturn(List.of());
		given(contractRepository.save(any(Contract.class))).willAnswer(call -> call.getArgument(0));

		service.create(1L, 2L, START, PLANNED_END, RENT, 15);

		ArgumentCaptor<Contract> saved = ArgumentCaptor.forClass(Contract.class);
		verify(contractRepository).save(saved.capture());
		Contract contract = saved.getValue();
		assertThat(contract.getTenant()).isSameAs(tenant);
		assertThat(contract.getGarage()).isSameAs(garage);
		assertThat(contract.getStartDate()).isEqualTo(START);
		assertThat(contract.getPlannedEndDate()).isEqualTo(PLANNED_END);
		assertThat(contract.getMonthlyRent()).isEqualByComparingTo(RENT);
		assertThat(contract.getPaymentDayOfMonth()).isEqualTo(15);
	}

	@Test
	void createRejectsAnArchivedTenantAndSavesNothing() {
		Tenant archived = new Tenant("Closed", null);
		archived.archive();
		given(tenantRepository.findById(1L)).willReturn(Optional.of(archived));

		assertThatThrownBy(() -> service.create(1L, 2L, START, PLANNED_END, RENT, 15))
			.isInstanceOf(IllegalStateException.class);

		verify(contractRepository, never()).save(any());
	}

	@Test
	void createRejectsAnArchivedGarageAndSavesNothing() {
		Tenant tenant = new Tenant("Acme Co", null);
		Garage archived = new Garage(new Location("Downtown"), "A-1", RENT);
		archived.archive();
		given(tenantRepository.findById(1L)).willReturn(Optional.of(tenant));
		given(garageRepository.findById(2L)).willReturn(Optional.of(archived));

		assertThatThrownBy(() -> service.create(1L, 2L, START, PLANNED_END, RENT, 15))
			.isInstanceOf(IllegalStateException.class);

		verify(contractRepository, never()).save(any());
	}

	@Test
	void createRejectsAPlannedEndBeforeTheStart() {
		Tenant tenant = new Tenant("Acme Co", null);
		Garage garage = new Garage(new Location("Downtown"), "A-1", RENT);
		given(tenantRepository.findById(1L)).willReturn(Optional.of(tenant));
		given(garageRepository.findById(2L)).willReturn(Optional.of(garage));

		assertThatThrownBy(() -> service.create(1L, 2L, START, START.minusDays(1), RENT, 15))
			.isInstanceOf(IllegalStateException.class);

		verify(contractRepository, never()).save(any());
	}

	@Test
	void createRejectsAPaymentDayOutsideOneToTwentyEight() {
		Tenant tenant = new Tenant("Acme Co", null);
		Garage garage = new Garage(new Location("Downtown"), "A-1", RENT);
		given(tenantRepository.findById(1L)).willReturn(Optional.of(tenant));
		given(garageRepository.findById(2L)).willReturn(Optional.of(garage));

		assertThatThrownBy(() -> service.create(1L, 2L, START, PLANNED_END, RENT, 0))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> service.create(1L, 2L, START, PLANNED_END, RENT, 29))
			.isInstanceOf(IllegalStateException.class);

		verify(contractRepository, never()).save(any());
	}

	@Test
	void createRejectsAnOverlappingWindowAndSavesNothing() {
		Tenant tenant = new Tenant("Acme Co", null);
		Garage garage = new Garage(new Location("Downtown"), "A-1", RENT);
		// Existing non-ended contract Jan–Dec; the proposed Jun–Dec window intersects it.
		Contract existing = new Contract(tenant, garage, START, PLANNED_END, RENT, 1);
		given(tenantRepository.findById(1L)).willReturn(Optional.of(tenant));
		given(garageRepository.findById(2L)).willReturn(Optional.of(garage));
		given(contractRepository.findByGarageIdAndEndedOnIsNull(2L)).willReturn(List.of(existing));

		assertThatThrownBy(() -> service.create(
				1L, 2L, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 12, 31), RENT, 15))
			.isInstanceOf(IllegalStateException.class);

		verify(contractRepository, never()).save(any());
	}

	@Test
	void createAcceptsANonOverlappingWindow() {
		Tenant tenant = new Tenant("Acme Co", null);
		Garage garage = new Garage(new Location("Downtown"), "A-1", RENT);
		// Existing window ends 2026-06-30; the proposed window starts the next day — no intersection.
		Contract existing = new Contract(tenant, garage, START, LocalDate.of(2026, 6, 30), RENT, 1);
		given(tenantRepository.findById(1L)).willReturn(Optional.of(tenant));
		given(garageRepository.findById(2L)).willReturn(Optional.of(garage));
		given(contractRepository.findByGarageIdAndEndedOnIsNull(2L)).willReturn(List.of(existing));
		given(contractRepository.save(any(Contract.class))).willAnswer(call -> call.getArgument(0));

		service.create(1L, 2L, LocalDate.of(2026, 7, 1), PLANNED_END, RENT, 15);

		verify(contractRepository).save(any(Contract.class));
	}

	@Test
	void createRejectsAWindowTouchingAnExistingOneOnASingleDay() {
		Tenant tenant = new Tenant("Acme Co", null);
		Garage garage = new Garage(new Location("Downtown"), "A-1", RENT);
		// Existing window ends 2026-06-30; the proposed window starts on that same day — the inclusive
		// bounds make this single-day touch an overlap, so it must be rejected.
		Contract existing = new Contract(tenant, garage, START, LocalDate.of(2026, 6, 30), RENT, 1);
		given(tenantRepository.findById(1L)).willReturn(Optional.of(tenant));
		given(garageRepository.findById(2L)).willReturn(Optional.of(garage));
		given(contractRepository.findByGarageIdAndEndedOnIsNull(2L)).willReturn(List.of(existing));

		assertThatThrownBy(() -> service.create(
				1L, 2L, LocalDate.of(2026, 6, 30), PLANNED_END, RENT, 15))
			.isInstanceOf(IllegalStateException.class);

		verify(contractRepository, never()).save(any());
	}

	@Test
	void endEarlySetsTheEndedOnDateAndSavesWithoutDeleting() {
		Contract contract = new Contract(new Tenant("Acme Co", null),
			new Garage(new Location("Downtown"), "A-1", RENT), START, PLANNED_END, RENT, 1);
		given(contractRepository.findById(1L)).willReturn(Optional.of(contract));

		LocalDate actualEnd = LocalDate.of(2026, 6, 30);
		service.endEarly(1L, actualEnd);

		assertThat(contract.getEndedOn()).isEqualTo(actualEnd);
		verify(contractRepository).save(contract);

		// R4: ending is a stamp, never a delete.
		verify(contractRepository, never()).delete(any());
		verify(contractRepository, never()).deleteById(anyLong());
		verify(contractRepository, never()).deleteAll();
	}

	@Test
	void endEarlyPropagatesTheEntityRejectionOfAnOutOfRangeActualEnd() {
		Contract contract = new Contract(new Tenant("Acme Co", null),
			new Garage(new Location("Downtown"), "A-1", RENT), START, PLANNED_END, RENT, 1);
		given(contractRepository.findById(1L)).willReturn(Optional.of(contract));

		assertThatThrownBy(() -> service.endEarly(1L, PLANNED_END.plusDays(1)))
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(contract.isEnded()).isFalse();
		verify(contractRepository, never()).save(any());
	}

	@Test
	void rentedGarageIdsKeepsOnlyGaragesWithACurrentlyActiveContract() {
		LocalDate today = LocalDate.of(2026, 6, 7);
		// Active now: started in the past, planned end in the future, not ended.
		Contract active = contractOnGarage(10L, today.minusMonths(1), today.plusMonths(1), null);
		// Future start: not yet active.
		Contract future = contractOnGarage(20L, today.plusDays(1), today.plusMonths(2), null);
		// Ended within its window: not active even though its planned end is in the future.
		Contract ended = contractOnGarage(30L, today.minusMonths(2), today.plusMonths(1),
			today.minusDays(1));
		List<Long> garageIds = List.of(10L, 20L, 30L);
		given(contractRepository.findNonEndedByGarageIdIn(garageIds))
			.willReturn(List.of(active, future, ended));

		Set<Long> rented = service.rentedGarageIds(garageIds, today);

		assertThat(rented).containsExactly(10L);
	}

	@Test
	void rentedGarageIdsReturnsEmptyForNoGaragesWithoutQuerying() {
		assertThat(service.rentedGarageIds(List.of(), LocalDate.of(2026, 6, 7))).isEmpty();
		verify(contractRepository, never()).findNonEndedByGarageIdIn(any());
	}

	@Test
	void historyForGarageDelegatesToTheNewestFirstFinder() {
		Contract contract = new Contract(new Tenant("Acme Co", null),
			new Garage(new Location("Downtown"), "A-1", RENT), START, PLANNED_END, RENT, 1);
		given(contractRepository.findByGarageIdOrderByStartDateDesc(2L)).willReturn(List.of(contract));

		assertThat(service.historyForGarage(2L)).containsExactly(contract);
	}

	@Test
	void forTenantDelegatesToTheNewestFirstFinder() {
		Contract contract = new Contract(new Tenant("Acme Co", null),
			new Garage(new Location("Downtown"), "A-1", RENT), START, PLANNED_END, RENT, 1);
		given(contractRepository.findByTenantIdOrderByStartDateDesc(1L)).willReturn(List.of(contract));

		assertThat(service.forTenant(1L)).containsExactly(contract);
	}

	// A real Contract whose garage reports a specific id, so rentedGarageIds' real isActiveOn date
	// logic is exercised while the collected garage id stays controllable (entities have no id in a
	// DB-free test).
	private static Contract contractOnGarage(Long garageId, LocalDate start, LocalDate plannedEnd,
			LocalDate endedOn) {
		Garage garage = mock(Garage.class);
		given(garage.getId()).willReturn(garageId);
		Contract contract = new Contract(new Tenant("Acme Co", null), garage, start, plannedEnd, RENT, 1);
		if (endedOn != null) {
			contract.endEarly(endedOn);
		}
		return contract;
	}
}
