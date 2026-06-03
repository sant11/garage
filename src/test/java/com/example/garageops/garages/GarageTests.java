package com.example.garageops.garages;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.example.garageops.locations.Location;

/**
 * Locks {@link Garage} object behavior DB-free, mirroring {@code ArchivableEntityTests}: the
 * problem flag toggles via {@code markProblem}/{@code clearProblem}, {@code edit} updates
 * label/rent, and {@code archive()} is idempotent. No database, no Spring context.
 */
class GarageTests {

	private Garage newGarage() {
		return new Garage(new Location("Downtown"), "A-1", new BigDecimal("250.00"));
	}

	@Test
	void newGarageIsFreeAndNotArchived() {
		Garage garage = newGarage();

		assertThat(garage.getLabel()).isEqualTo("A-1");
		assertThat(garage.getMonthlyRent()).isEqualByComparingTo("250.00");
		assertThat(garage.isProblem()).isFalse();
		assertThat(garage.getProblemReason()).isNull();
		assertThat(garage.isArchived()).isFalse();
	}

	@Test
	void editUpdatesLabelAndRent() {
		Garage garage = newGarage();

		garage.edit("A-2", new BigDecimal("300.00"));

		assertThat(garage.getLabel()).isEqualTo("A-2");
		assertThat(garage.getMonthlyRent()).isEqualByComparingTo("300.00");
	}

	@Test
	void markProblemThenClearProblemTogglesTheFlagAndReason() {
		Garage garage = newGarage();

		garage.markProblem("Door stuck");

		assertThat(garage.isProblem()).isTrue();
		assertThat(garage.getProblemReason()).isEqualTo("Door stuck");

		garage.clearProblem();

		assertThat(garage.isProblem()).isFalse();
		assertThat(garage.getProblemReason()).isNull();
	}

	@Test
	void archiveIsIdempotent_preservesOriginalArchiveMoment() throws InterruptedException {
		Garage garage = newGarage();
		garage.archive();
		Instant firstStamp = garage.getArchivedAt();
		Thread.sleep(5);

		garage.archive();

		assertThat(garage.isArchived()).isTrue();
		assertThat(garage.getArchivedAt()).isEqualTo(firstStamp);
	}
}
