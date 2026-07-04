package com.example.garageops.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * Base for all archivable domain entities (tenants, garages, contracts, …), establishing the
 * FR-021 archive-only convention.
 *
 * <p><b>Archive is a visible, queryable state — not a row-hiding filter.</b> Archiving stamps
 * {@code archived_at} and <em>retains</em> the row; it is never a delete. Archived records stay
 * readable so history views keep working (FR-008 past contracts, FR-011 full rental history).
 * This is deliberately not Hibernate {@code @SoftDelete}, which would globally hide archived rows.
 *
 * <p>Audit timestamps come from JPA lifecycle callbacks ({@code @PrePersist}/{@code @PreUpdate}),
 * not Spring Data {@code @EnableJpaAuditing} — the latter needs an {@code EntityManagerFactory} at
 * context startup, which the DB-free test profile excludes.
 *
 * <p>Slices that extend this type must add {@code archived_at}/{@code created_at}/{@code updated_at}
 * columns to their own Flyway migrations, declared as {@code timestamptz}: Hibernate maps
 * {@link Instant} to {@code TIMESTAMP WITH TIME ZONE}, so a plain {@code timestamp} column would
 * fail {@code ddl-auto=validate}.
 */
@MappedSuperclass
public abstract class ArchivableEntity extends BaseEntity {

	@Column(name = "archived_at")
	private Instant archivedAt;

	@Column(name = "created_at", updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at")
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}

	/**
	 * Archive this record. Idempotent: a second call leaves the original archive moment intact.
	 */
	public void archive() {
		if (archivedAt == null) {
			archivedAt = Instant.now();
		}
	}

	/** @return {@code true} once {@link #archive()} has stamped {@code archived_at}. */
	public boolean isArchived() {
		return archivedAt != null;
	}

	public Instant getArchivedAt() {
		return archivedAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}
}
