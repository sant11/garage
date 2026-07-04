package com.example.garageops.persistence;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

/**
 * Universal surrogate-key base every entity extends. Separates "has an id" from "is archivable"
 * (see {@link ArchivableEntity}).
 *
 * <p>The {@code IDENTITY} strategy maps onto Postgres {@code BIGSERIAL} / {@code GENERATED AS
 * IDENTITY} columns, which the database owns.
 */
@MappedSuperclass
public abstract class BaseEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	protected Long getId() {
		return id;
	}
}
