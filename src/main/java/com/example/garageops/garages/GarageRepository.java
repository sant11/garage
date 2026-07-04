package com.example.garageops.garages;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access for {@link Garage}. The single-location active finder backs both the
 * per-location grid and the cascade-archive stamp pass ({@code LocationService#archive}); the
 * multi-location active finder batch-loads the whole portfolio grid in one query.
 */
public interface GarageRepository extends JpaRepository<Garage, Long> {

	List<Garage> findByLocationIdAndArchivedAtIsNull(Long locationId);

	/**
	 * Active garages across several locations in one query, to batch-load the portfolio grid. The
	 * {@code JOIN FETCH g.location} is required: {@code GarageService#listActiveByLocations} groups the
	 * result by {@code g.getLocation().getId()} outside the session ({@code open-in-view=false}, and the
	 * service is not {@code @Transactional}), so the LAZY association must be initialized eagerly here.
	 */
	@Query("select g from Garage g join fetch g.location "
			+ "where g.location.id in :locationIds and g.archivedAt is null")
	List<Garage> findByLocationIdInAndArchivedAtIsNull(@Param("locationIds") List<Long> locationIds);

	/**
	 * A single garage with its {@code location} fetched, backing the {@code garages/:id} detail view.
	 * The {@code JOIN FETCH g.location} is required: the view renders the location name outside the
	 * session ({@code open-in-view=false}), so the LAZY association must be initialized eagerly here.
	 */
	@Query("select g from Garage g join fetch g.location where g.id = :id")
	Optional<Garage> findWithLocationById(@Param("id") Long id);
}
