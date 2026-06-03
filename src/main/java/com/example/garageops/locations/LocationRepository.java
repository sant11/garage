package com.example.garageops.locations;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data access for {@link Location}. The active-only finder backs the default portfolio view;
 * archived rows stay reachable via the inherited {@code findById}/{@code findAll} for future history.
 */
public interface LocationRepository extends JpaRepository<Location, Long> {

	List<Location> findByArchivedAtIsNullOrderByNameAsc();
}
