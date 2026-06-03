package com.example.garageops.garages;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access for {@link Garage}. The active-only finder backs the per-location grid; the
 * all-by-location finder feeds the cascade-archive pass (which must reach every child to stamp it).
 */
public interface GarageRepository extends JpaRepository<Garage, Long> {

	List<Garage> findByLocationIdAndArchivedAtIsNull(Long locationId);

	List<Garage> findByLocationId(Long locationId);
}
