package com.example.garageops.locations;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Locks {@link Location} object behavior DB-free, mirroring {@code ArchivableEntityTests}: rename
 * updates the name and {@code archive()} is idempotent (the inherited FR-021 contract must not
 * regress for this concrete entity).
 */
class LocationTests {

	@Test
	void newLocationCarriesItsNameAndIsNotArchived() {
		Location location = new Location("Downtown");

		assertThat(location.getName()).isEqualTo("Downtown");
		assertThat(location.isArchived()).isFalse();
	}

	@Test
	void renameUpdatesTheName() {
		Location location = new Location("Downtown");

		location.rename("Old Town");

		assertThat(location.getName()).isEqualTo("Old Town");
	}

	@Test
	void archiveIsIdempotent_preservesOriginalArchiveMoment() throws InterruptedException {
		Location location = new Location("Downtown");
		location.archive();
		Instant firstStamp = location.getArchivedAt();
		Thread.sleep(5);

		location.archive();

		assertThat(location.isArchived()).isTrue();
		assertThat(location.getArchivedAt()).isEqualTo(firstStamp);
	}
}
