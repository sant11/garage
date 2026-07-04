package com.example.garageops.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractRepository;
import com.example.garageops.contracts.ContractRepository.GarageLastEnded;
import com.example.garageops.contracts.ContractService;
import com.example.garageops.garages.Garage;
import com.example.garageops.garages.GarageService;
import com.example.garageops.locations.Location;
import com.example.garageops.locations.LocationService;
import com.example.garageops.tenants.Tenant;

/**
 * Verifies the two S-06 derivations in {@link DashboardService} — vacant-garage and ending-soon —
 * with mocked sibling services and repository, a fixed {@link Clock}, no Spring context (mirroring
 * {@code OverdueServiceTests}). The clock is pinned to Warsaw on 2026-06-20, so "today" is
 * deterministic and every day-count is exact.
 *
 * <p>The SQL-level exclusions of the ending-soon window (already-ended, archived, day-31) live in
 * {@code ContractRepository.findEndingBetween}'s JPQL and are confirmed manually against a real DB;
 * here the service contract is that it asks for exactly {@code [today, today+30]} and maps the rows
 * faithfully.
 */
class DashboardServiceTests {

	private static final ZoneId WARSAW = ZoneId.of("Europe/Warsaw");
	private static final LocalDate TODAY = LocalDate.of(2026, 6, 20);
	private static final Long LOCATION_ID = 100L;

	private final Clock clock = Clock.fixed(Instant.parse("2026-06-20T10:00:00Z"), WARSAW);
	private final LocationService locationService = mock(LocationService.class);
	private final GarageService garageService = mock(GarageService.class);
	private final ContractService contractService = mock(ContractService.class);
	private final ContractRepository contractRepository = mock(ContractRepository.class);
	private final DashboardService service = new DashboardService(
		clock, locationService, garageService, contractService, providerOf(contractRepository));

	@SuppressWarnings("unchecked")
	private static <T> ObjectProvider<T> providerOf(T bean) {
		ObjectProvider<T> provider = mock(ObjectProvider.class);
		given(provider.getObject()).willReturn(bean);
		return provider;
	}

	@Test
	void vacantGarageNeverRentedUsesCreationDateFallbackForDaysVacant() {
		// Created 2026-06-01 (Warsaw); no ended contract → fallback to creation date.
		Garage garage = garage(1L, "A-1", "Downtown", Instant.parse("2026-06-01T00:00:00Z"));
		givenActivePortfolio(garage);
		given(contractService.rentedGarageIds(anyList(), any())).willReturn(Set.of());
		given(contractRepository.findLastEndedByGarageIdIn(anyList())).willReturn(List.of());

		List<VacantGarageRow> rows = service.vacantGarages();

		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.garageId()).isEqualTo(1L);
			assertThat(row.garageLabel()).isEqualTo("A-1");
			assertThat(row.locationName()).isEqualTo("Downtown");
			assertThat(row.daysVacant()).isEqualTo(19L); // 2026-06-01 → 2026-06-20
		});
	}

	@Test
	void vacantGarageVacatedUsesMostRecentEndedOnForDaysVacant() {
		Garage garage = garage(1L, "A-1", "Downtown", Instant.parse("2026-01-01T00:00:00Z"));
		givenActivePortfolio(garage);
		given(contractService.rentedGarageIds(anyList(), any())).willReturn(Set.of());
		// Last tenant moved out 2026-06-10 — the most recent end date wins over the creation date.
		// Build the projection mock before the outer stubbing call (nested stubbing is illegal).
		GarageLastEnded vacatedOn = lastEnded(1L, LocalDate.of(2026, 6, 10));
		given(contractRepository.findLastEndedByGarageIdIn(anyList())).willReturn(List.of(vacatedOn));

		List<VacantGarageRow> rows = service.vacantGarages();

		assertThat(rows).singleElement().satisfies(row ->
			assertThat(row.daysVacant()).isEqualTo(10L)); // 2026-06-10 → 2026-06-20
	}

	@Test
	void vacantExcludesAGarageWithAContractActiveToday() {
		Garage vacant = garage(1L, "A-1", "Downtown", Instant.parse("2026-06-01T00:00:00Z"));
		Garage rented = garage(2L, "A-2", "Downtown", Instant.parse("2026-06-01T00:00:00Z"));
		givenActivePortfolio(vacant, rented);
		given(contractService.rentedGarageIds(anyList(), any())).willReturn(Set.of(2L));
		given(contractRepository.findLastEndedByGarageIdIn(anyList())).willReturn(List.of());

		List<VacantGarageRow> rows = service.vacantGarages();

		assertThat(rows).singleElement().satisfies(row -> assertThat(row.garageId()).isEqualTo(1L));
	}

	@Test
	void vacantRowsAreSortedByDaysVacantDescending() {
		Garage shorter = garage(1L, "A-1", "Downtown", Instant.parse("2026-06-15T00:00:00Z")); // 5 days
		Garage longer = garage(2L, "A-2", "Downtown", Instant.parse("2026-06-01T00:00:00Z")); // 19 days
		givenActivePortfolio(shorter, longer);
		given(contractService.rentedGarageIds(anyList(), any())).willReturn(Set.of());
		given(contractRepository.findLastEndedByGarageIdIn(anyList())).willReturn(List.of());

		List<VacantGarageRow> rows = service.vacantGarages();

		assertThat(rows).extracting(VacantGarageRow::garageId).containsExactly(2L, 1L);
		assertThat(rows).extracting(VacantGarageRow::daysVacant).containsExactly(19L, 5L);
	}

	@Test
	void vacantGaragesIsEmptyForAnEmptyPortfolio() {
		given(locationService.listActive()).willReturn(List.of());

		assertThat(service.vacantGarages()).isEmpty();
	}

	@Test
	void endingSoonQueriesTheThirtyDayWindowAndMapsDaysRemaining() {
		Contract contract = endingContract(1L, 7L, "A-1", "Acme Co", LocalDate.of(2026, 6, 30));
		given(contractRepository.findEndingBetween(any(), any())).willReturn(List.of(contract));

		List<EndingSoonRow> rows = service.endingSoon();

		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.contractId()).isEqualTo(1L);
			assertThat(row.garageId()).isEqualTo(7L);
			assertThat(row.garageLabel()).isEqualTo("A-1");
			assertThat(row.tenantName()).isEqualTo("Acme Co");
			assertThat(row.plannedEndDate()).isEqualTo(LocalDate.of(2026, 6, 30));
			assertThat(row.daysRemaining()).isEqualTo(10L); // 2026-06-20 → 2026-06-30
		});
		// The service controls the window: [today, today + 30 days] inclusive.
		verify(contractRepository).findEndingBetween(TODAY, TODAY.plusDays(30));
	}

	@Test
	void endingSoonRowsAreSortedByDaysRemainingAscending() {
		Contract later = endingContract(1L, 7L, "A-1", "Acme Co", LocalDate.of(2026, 7, 10)); // 20 days
		Contract sooner = endingContract(2L, 8L, "A-2", "Beta Ltd", LocalDate.of(2026, 6, 25)); // 5 days
		given(contractRepository.findEndingBetween(any(), any())).willReturn(List.of(later, sooner));

		List<EndingSoonRow> rows = service.endingSoon();

		assertThat(rows).extracting(EndingSoonRow::contractId).containsExactly(2L, 1L);
		assertThat(rows).extracting(EndingSoonRow::daysRemaining).containsExactly(5L, 20L);
	}

	// Wire the active-portfolio path: one active location holding the given garages, batch-loaded by
	// GarageService keyed on the location id.
	private void givenActivePortfolio(Garage... garages) {
		Location location = mock(Location.class);
		given(location.getId()).willReturn(LOCATION_ID);
		given(locationService.listActive()).willReturn(List.of(location));
		given(garageService.listActiveByLocations(anyList()))
			.willReturn(Map.of(LOCATION_ID, List.of(garages)));
	}

	// A mocked Garage so its surrogate id, label, location name, and creation instant are controllable
	// (a DB-free entity has no id and a null createdAt); the service reads only these getters off it.
	private static Garage garage(long id, String label, String locationName, Instant createdAt) {
		Garage garage = mock(Garage.class);
		given(garage.getId()).willReturn(id);
		given(garage.getLabel()).willReturn(label);
		given(garage.getCreatedAt()).willReturn(createdAt);
		Location location = mock(Location.class);
		given(location.getName()).willReturn(locationName);
		given(garage.getLocation()).willReturn(location);
		return garage;
	}

	private static GarageLastEnded lastEnded(long garageId, LocalDate date) {
		GarageLastEnded projection = mock(GarageLastEnded.class);
		given(projection.getGarageId()).willReturn(garageId);
		given(projection.getLastEnded()).willReturn(date);
		return projection;
	}

	// A mocked Contract whose only read getters are the ones the ending-soon mapping touches.
	private static Contract endingContract(long id, long garageId, String garageLabel,
			String tenantName, LocalDate plannedEnd) {
		Contract contract = mock(Contract.class);
		given(contract.getId()).willReturn(id);
		given(contract.getPlannedEndDate()).willReturn(plannedEnd);
		Garage garage = mock(Garage.class);
		given(garage.getId()).willReturn(garageId);
		given(garage.getLabel()).willReturn(garageLabel);
		given(contract.getGarage()).willReturn(garage);
		Tenant tenant = mock(Tenant.class);
		given(tenant.getName()).willReturn(tenantName);
		given(contract.getTenant()).willReturn(tenant);
		return contract;
	}
}
