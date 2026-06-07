package com.example.garageops.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.example.garageops.garages.Garage;
import com.example.garageops.locations.Location;
import com.example.garageops.tenants.Tenant;

/**
 * Locks {@link Contract} object behavior DB-free, mirroring {@code GarageTests} /
 * {@code ArchivableEntityTests}: {@code endEarly} validates the actual-end window and rejects
 * double-ending, {@code isActiveOn} derives the active state across the date boundaries, and
 * {@code isEnded} reflects {@code endedOn}. No database, no Spring context.
 */
class ContractTests {

	private static final LocalDate START = LocalDate.of(2026, 1, 1);
	private static final LocalDate PLANNED_END = LocalDate.of(2026, 12, 31);

	private Contract newContract() {
		Tenant tenant = new Tenant("Acme Co.", null);
		Garage garage = new Garage(new Location("Downtown"), "A-1", new BigDecimal("250.00"));
		return new Contract(tenant, garage, START, PLANNED_END, new BigDecimal("250.00"), 1);
	}

	@Test
	void newContractIsNotEndedAndNotArchived() {
		Contract contract = newContract();

		assertThat(contract.isEnded()).isFalse();
		assertThat(contract.getEndedOn()).isNull();
		assertThat(contract.isArchived()).isFalse();
	}

	@Test
	void endEarlyRecordsTheActualMoveOutDate() {
		Contract contract = newContract();
		LocalDate actualEnd = LocalDate.of(2026, 6, 30);

		contract.endEarly(actualEnd);

		assertThat(contract.isEnded()).isTrue();
		assertThat(contract.getEndedOn()).isEqualTo(actualEnd);
	}

	@Test
	void endEarlyAcceptsTheBoundaryDates() {
		Contract atStart = newContract();
		atStart.endEarly(START);
		assertThat(atStart.getEndedOn()).isEqualTo(START);

		Contract atPlannedEnd = newContract();
		atPlannedEnd.endEarly(PLANNED_END);
		assertThat(atPlannedEnd.getEndedOn()).isEqualTo(PLANNED_END);
	}

	@Test
	void endEarlyRejectsAnActualEndAfterThePlannedEnd() {
		Contract contract = newContract();

		assertThatThrownBy(() -> contract.endEarly(PLANNED_END.plusDays(1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(contract.isEnded()).isFalse();
	}

	@Test
	void endEarlyRejectsAnActualEndBeforeTheStart() {
		Contract contract = newContract();

		assertThatThrownBy(() -> contract.endEarly(START.minusDays(1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(contract.isEnded()).isFalse();
	}

	@Test
	void endEarlyRejectsEndingAnAlreadyEndedContract() {
		Contract contract = newContract();
		contract.endEarly(LocalDate.of(2026, 6, 30));

		assertThatThrownBy(() -> contract.endEarly(LocalDate.of(2026, 7, 31)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(contract.getEndedOn()).isEqualTo(LocalDate.of(2026, 6, 30));
	}

	@Test
	void isActiveOnIsFalseBeforeTheStartDate() {
		Contract contract = newContract();

		assertThat(contract.isActiveOn(START.minusDays(1))).isFalse();
	}

	@Test
	void isActiveOnIsTrueOnTheStartDate() {
		Contract contract = newContract();

		assertThat(contract.isActiveOn(START)).isTrue();
	}

	@Test
	void isActiveOnIsTrueOnThePlannedEndDate() {
		Contract contract = newContract();

		assertThat(contract.isActiveOn(PLANNED_END)).isTrue();
	}

	@Test
	void isActiveOnIsFalseAfterThePlannedEndDate() {
		Contract contract = newContract();

		assertThat(contract.isActiveOn(PLANNED_END.plusDays(1))).isFalse();
	}

	@Test
	void isActiveOnIsFalseOnceEnded() {
		Contract contract = newContract();
		contract.endEarly(LocalDate.of(2026, 6, 30));

		assertThat(contract.isActiveOn(LocalDate.of(2026, 6, 1))).isFalse();
	}
}
