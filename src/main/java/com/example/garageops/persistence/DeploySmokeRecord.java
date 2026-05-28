package com.example.garageops.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * Temporary JPA-stack proof: maps the existing {@code deploy_smoke_test} table (from the immutable
 * {@code V1__init.sql} migration) so the JPA wiring and {@code ddl-auto=validate} are exercised
 * end-to-end against a real table, without inventing any domain schema.
 *
 * <p>Extends {@link BaseEntity} (id only), <b>not</b> {@link ArchivableEntity}: the smoke table has
 * no {@code archived_at}/{@code created_at}/{@code updated_at} columns, and V1 is immutable, so an
 * archivable mapping would fail {@code validate}.
 *
 * <p>Delete this entity and its repository once the first domain table (S-02) supersedes the smoke
 * table.
 */
@Entity
@Table(name = "deploy_smoke_test")
public class DeploySmokeRecord extends BaseEntity {

	// DB default NOW() owns this column; the mapping is read-only.
	@Column(name = "deployed_at", insertable = false, updatable = false)
	private Instant deployedAt;

	@Column(name = "note")
	private String note;

	protected DeploySmokeRecord() {
		// JPA requires a no-arg constructor.
	}

	public Instant getDeployedAt() {
		return deployedAt;
	}

	public String getNote() {
		return note;
	}
}
