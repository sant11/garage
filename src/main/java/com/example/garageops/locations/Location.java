package com.example.garageops.locations;

import com.example.garageops.persistence.ArchivableEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

/**
 * A portfolio location (S-02) — a named place that groups garages. Archivable per FR-021: archiving
 * stamps {@code archived_at} and retains the row (the cascade-stamp of its garages lives in
 * {@code LocationService}, never a JPA delete cascade).
 *
 * <p>Single-tenant by design: no {@code owner_id} FK (PRD Non-Goal, AGENTS hard rule). Column mapping
 * must match {@code V3__locations_and_garages.sql} exactly ({@code ddl-auto=validate}).
 */
@Entity
@Table(name = "locations")
public class Location extends ArchivableEntity {

	@NotBlank
	@Column(name = "name", nullable = false)
	private String name;

	protected Location() {
		// JPA requires a no-arg constructor.
	}

	public Location(String name) {
		this.name = name;
	}

	/**
	 * Surrogate key, widened to {@code public} so the portfolio view can pass it to
	 * {@code LocationService} (the protected {@code BaseEntity#getId} is reachable only by
	 * subclasses, and views are not).
	 */
	@Override
	public Long getId() {
		return super.getId();
	}

	public String getName() {
		return name;
	}

	/** Rename this location. */
	public void rename(String name) {
		this.name = name;
	}
}
