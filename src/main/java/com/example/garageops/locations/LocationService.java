package com.example.garageops.locations;

import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.garageops.garages.Garage;
import com.example.garageops.garages.GarageRepository;

import jakarta.persistence.EntityNotFoundException;

/**
 * Owns the location lifecycle (S-02): add, rename, list active, and <b>archive with cascade</b>.
 *
 * <p>Archive is the load-bearing operation: per FR-021 and the {@code ArchivableEntity} convention,
 * it stamps {@code archived_at} and <em>retains</em> rows — never deletes. Cascade is an explicit
 * service-layer stamp pass (load the location's active garages, {@code archive()} each, save all),
 * deliberately <b>not</b> a JPA {@code CascadeType.REMOVE}, so children are retained by construction
 * (R4).
 *
 * <p>Both repositories are injected as {@link ObjectProvider}s and resolved per call, mirroring
 * {@code account/OwnerDetailsService}: this keeps the bean constructible in the DB-free test
 * context, where JPA autoconfig (and thus the repository beans) is excluded by convention. In
 * production the repositories are always present.
 */
@Service
public class LocationService {

	private final ObjectProvider<LocationRepository> locationRepository;
	private final ObjectProvider<GarageRepository> garageRepository;

	public LocationService(
			ObjectProvider<LocationRepository> locationRepository,
			ObjectProvider<GarageRepository> garageRepository) {
		this.locationRepository = locationRepository;
		this.garageRepository = garageRepository;
	}

	/** Add a new location. */
	public Location add(String name) {
		return locations().save(new Location(name));
	}

	/** Rename an existing location. */
	public void rename(Long id, String name) {
		Location location = require(id);
		location.rename(name);
		locations().save(location);
	}

	/** @return active (non-archived) locations, name-ordered, for the default portfolio view. */
	public List<Location> listActive() {
		return locations().findByArchivedAtIsNullOrderByNameAsc();
	}

	/**
	 * Archive a location and cascade-stamp its active garages. Loads the location, stamps it, then
	 * loads its active garages and stamps each — a retain pass, never a delete. {@code archive()} is
	 * idempotent, so a re-run leaves existing archive moments intact.
	 */
	@Transactional
	public void archive(Long locationId) {
		Location location = require(locationId);
		location.archive();
		locations().save(location);

		List<Garage> activeGarages = garages().findByLocationIdAndArchivedAtIsNull(locationId);
		activeGarages.forEach(Garage::archive);
		garages().saveAll(activeGarages);
	}

	private Location require(Long id) {
		return locations().findById(id)
			.orElseThrow(() -> new EntityNotFoundException("Unknown location: " + id));
	}

	private LocationRepository locations() {
		return locationRepository.getObject();
	}

	private GarageRepository garages() {
		return garageRepository.getObject();
	}
}
