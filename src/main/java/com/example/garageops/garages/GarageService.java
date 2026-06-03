package com.example.garageops.garages;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.example.garageops.locations.Location;
import com.example.garageops.locations.LocationRepository;

import jakarta.persistence.EntityNotFoundException;

/**
 * Owns the garage lifecycle (S-02): add under a location, edit label/rent, mark/clear the problem
 * flag, archive, and list by location.
 *
 * <p>{@code add} resolves the parent {@link Location} and rejects an archived one — a garage cannot
 * be created under a retired location. {@code archive} stamps and retains (FR-021); it never
 * deletes.
 *
 * <p>Both repositories are injected as {@link ObjectProvider}s and resolved per call, mirroring
 * {@code account/OwnerDetailsService}: this keeps the bean constructible in the DB-free test
 * context, where JPA autoconfig (and thus the repository beans) is excluded by convention. In
 * production the repositories are always present.
 */
@Service
public class GarageService {

	private final ObjectProvider<GarageRepository> garageRepository;
	private final ObjectProvider<LocationRepository> locationRepository;

	public GarageService(
			ObjectProvider<GarageRepository> garageRepository,
			ObjectProvider<LocationRepository> locationRepository) {
		this.garageRepository = garageRepository;
		this.locationRepository = locationRepository;
	}

	/** Add a garage under a location. Rejects an archived parent location. */
	public Garage add(Long locationId, String label, BigDecimal monthlyRent) {
		Location location = locations().findById(locationId)
			.orElseThrow(() -> new EntityNotFoundException("Unknown location: " + locationId));
		if (location.isArchived()) {
			throw new IllegalStateException("Cannot add a garage to an archived location: " + locationId);
		}
		return garages().save(new Garage(location, label, monthlyRent));
	}

	/** Edit a garage's label and default monthly rent. */
	public void edit(Long garageId, String label, BigDecimal rent) {
		Garage garage = require(garageId);
		garage.edit(label, rent);
		garages().save(garage);
	}

	/** Flag a garage as having a problem, recording the free-text reason. */
	public void markProblem(Long garageId, String reason) {
		Garage garage = require(garageId);
		garage.markProblem(reason);
		garages().save(garage);
	}

	/** Clear a previously recorded problem on a garage. */
	public void clearProblem(Long garageId) {
		Garage garage = require(garageId);
		garage.clearProblem();
		garages().save(garage);
	}

	/** Archive a garage — stamps {@code archived_at} and retains the row (never deletes). */
	public void archive(Long garageId) {
		Garage garage = require(garageId);
		garage.archive();
		garages().save(garage);
	}

	/** @return active (non-archived) garages under a location, for the per-location grid. */
	public List<Garage> listActiveByLocation(Long locationId) {
		return garages().findByLocationIdAndArchivedAtIsNull(locationId);
	}

	private Garage require(Long id) {
		return garages().findById(id)
			.orElseThrow(() -> new EntityNotFoundException("Unknown garage: " + id));
	}

	private GarageRepository garages() {
		return garageRepository.getObject();
	}

	private LocationRepository locations() {
		return locationRepository.getObject();
	}
}
