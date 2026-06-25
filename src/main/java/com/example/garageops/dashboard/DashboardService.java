package com.example.garageops.dashboard;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.example.garageops.contracts.Contract;
import com.example.garageops.contracts.ContractRepository;
import com.example.garageops.contracts.ContractRepository.GarageLastEnded;
import com.example.garageops.contracts.ContractService;
import com.example.garageops.garages.Garage;
import com.example.garageops.garages.GarageService;
import com.example.garageops.locations.Location;
import com.example.garageops.locations.LocationService;

/**
 * Derives the two dashboard signals that are new in S-06 — <b>vacant garages</b> and
 * <b>contracts ending soon</b> (US-01, FR-016–FR-018) — as off-session-safe row records the landing
 * view can render directly. The third signal, overdue, is reused verbatim from
 * {@code OverdueService} and is not computed here.
 *
 * <p><b>"Today" flows from the injected {@link Clock}</b> (the same fixed-zone bean the overdue
 * engine uses), so both signals classify deterministically and CI-zone-independently — tests pin a
 * {@link Clock#fixed}.
 *
 * <p>Both reads compose the sibling feature services rather than touching repositories directly
 * where one already exists: {@link #vacantGarages()} subtracts {@link ContractService#rentedGarageIds}
 * from the active portfolio loaded via {@link LocationService}/{@link GarageService}, and resolves
 * each vacancy-since date from one batched aggregation ({@code findLastEndedByGarageIdIn}) rather
 * than a query per garage. {@link ContractRepository} is injected as an {@link ObjectProvider}
 * resolved per call, mirroring the sibling services so the bean stays constructible in the DB-free
 * test context. Reads are not {@code @Transactional} but resolve every fetched value before
 * returning.
 */
@Service
public class DashboardService {

	/** The forward window, in days, that counts as "ending soon" (FR-018). */
	private static final int ENDING_SOON_DAYS = 30;

	private final Clock clock;
	private final LocationService locationService;
	private final GarageService garageService;
	private final ContractService contractService;
	private final ObjectProvider<ContractRepository> contractRepository;

	public DashboardService(
			Clock clock,
			LocationService locationService,
			GarageService garageService,
			ContractService contractService,
			ObjectProvider<ContractRepository> contractRepository) {
		this.clock = clock;
		this.locationService = locationService;
		this.garageService = garageService;
		this.contractService = contractService;
		this.contractRepository = contractRepository;
	}

	/**
	 * @return every active garage with no contract running today, most-empty-first. Vacancy-since is
	 *         the garage's most recent contract end date, or — for a garage never rented (no ended
	 *         contract) — its creation date, both resolved in the clock's zone; {@code daysVacant} is
	 *         whole days from that date to today.
	 */
	public List<VacantGarageRow> vacantGarages() {
		LocalDate today = LocalDate.now(clock);

		List<Location> locations = locationService.listActive();
		if (locations.isEmpty()) {
			return List.of();
		}
		Map<Long, List<Garage>> garagesByLocation = garageService.listActiveByLocations(
			locations.stream().map(Location::getId).toList());
		List<Garage> allGarages = garagesByLocation.values().stream().flatMap(List::stream).toList();
		if (allGarages.isEmpty()) {
			return List.of();
		}

		Set<Long> rented = contractService.rentedGarageIds(
			allGarages.stream().map(Garage::getId).toList(), today);
		List<Garage> vacant = allGarages.stream()
			.filter(g -> !rented.contains(g.getId()))
			.toList();
		if (vacant.isEmpty()) {
			return List.of();
		}

		Map<Long, LocalDate> lastEndedByGarage = contracts()
			.findLastEndedByGarageIdIn(vacant.stream().map(Garage::getId).toList()).stream()
			.collect(Collectors.toMap(GarageLastEnded::getGarageId, GarageLastEnded::getLastEnded));

		return vacant.stream()
			.map(g -> {
				LocalDate vacantSince = lastEndedByGarage.getOrDefault(g.getId(), createdAsLocalDate(g));
				long daysVacant = ChronoUnit.DAYS.between(vacantSince, today);
				return new VacantGarageRow(g.getId(), g.getLabel(), g.getLocation().getName(), daysVacant);
			})
			.sorted(Comparator.comparingLong(VacantGarageRow::daysVacant).reversed())
			.toList();
	}

	/**
	 * @return active, non-archived contracts whose planned end falls within the next
	 *         {@value #ENDING_SOON_DAYS} days inclusive, soonest-first. {@code daysRemaining} is whole
	 *         days from today to the planned end date.
	 */
	public List<EndingSoonRow> endingSoon() {
		LocalDate today = LocalDate.now(clock);
		LocalDate windowEnd = today.plusDays(ENDING_SOON_DAYS);

		return contracts().findEndingBetween(today, windowEnd).stream()
			.map(c -> mapEndingSoon(c, today))
			.sorted(Comparator.comparingLong(EndingSoonRow::daysRemaining))
			.toList();
	}

	private EndingSoonRow mapEndingSoon(Contract contract, LocalDate today) {
		long daysRemaining = ChronoUnit.DAYS.between(today, contract.getPlannedEndDate());
		return new EndingSoonRow(contract.getId(), contract.getGarage().getId(),
			contract.getGarage().getLabel(), contract.getTenant().getName(),
			contract.getPlannedEndDate(), daysRemaining);
	}

	// Fallback vacancy-since for a garage with no ended contract (never rented, or only future/active
	// ones): its creation moment, resolved to a date in the clock's zone.
	private LocalDate createdAsLocalDate(Garage garage) {
		return garage.getCreatedAt().atZone(clock.getZone()).toLocalDate();
	}

	private ContractRepository contracts() {
		return contractRepository.getObject();
	}
}
