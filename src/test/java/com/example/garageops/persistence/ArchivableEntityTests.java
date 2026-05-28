package com.example.garageops.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Locks the FR-021 archive-only convention as pure object behavior. Archive is an explicit,
 * queryable state — never a row-hiding filter — and the JPA lifecycle callbacks own the
 * created/updated audit timestamps. Runs without a database or Spring context, so the contract
 * every archivable slice (S-02 garages, S-03 tenants, S-04 contracts) inherits cannot silently
 * regress.
 *
 * <p>The callbacks {@code onCreate} / {@code onUpdate} are package-private; this test lives in
 * {@code com.example.garageops.persistence} so it can invoke them directly without persistence.
 */
class ArchivableEntityTests {

	/** Minimal concrete subclass — no extra state, just makes the abstract base instantiable. */
	private static class Fixture extends ArchivableEntity {
	}

	@Test
	void newInstanceIsNotArchived() {
		Fixture e = new Fixture();

		assertThat(e.isArchived()).isFalse();
		assertThat(e.getArchivedAt()).isNull();
	}

	@Test
	void archiveStampsArchivedAtAndFlipsIsArchived() {
		Fixture e = new Fixture();
		Instant before = Instant.now();

		e.archive();

		assertThat(e.isArchived()).isTrue();
		assertThat(e.getArchivedAt()).isNotNull();
		assertThat(e.getArchivedAt()).isAfterOrEqualTo(before);
	}

	@Test
	void archiveIsIdempotent_preservesOriginalArchiveMoment() throws InterruptedException {
		Fixture e = new Fixture();
		e.archive();
		Instant firstStamp = e.getArchivedAt();
		// Let the clock advance so a re-stamp would be observably different if the guard regressed.
		Thread.sleep(5);

		e.archive();

		assertThat(e.getArchivedAt()).isEqualTo(firstStamp);
	}

	@Test
	void prePersistSetsBothCreatedAndUpdated() {
		Fixture e = new Fixture();

		e.onCreate();

		assertThat(e.getCreatedAt()).isNotNull();
		assertThat(e.getUpdatedAt()).isNotNull();
		assertThat(e.getCreatedAt()).isEqualTo(e.getUpdatedAt());
	}

	@Test
	void preUpdateAdvancesUpdatedAtAndLeavesCreatedAtUntouched() throws InterruptedException {
		Fixture e = new Fixture();
		e.onCreate();
		Instant originalCreated = e.getCreatedAt();
		Instant originalUpdated = e.getUpdatedAt();
		// Ensure the clock ticks so updatedAt is strictly after the original stamp.
		Thread.sleep(5);

		e.onUpdate();

		assertThat(e.getCreatedAt()).isEqualTo(originalCreated);
		assertThat(e.getUpdatedAt()).isAfter(originalUpdated);
	}
}
